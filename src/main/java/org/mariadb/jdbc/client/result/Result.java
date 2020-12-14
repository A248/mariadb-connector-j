/*
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc.client.result;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.*;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.client.socket.PacketReader;
import org.mariadb.jdbc.codec.BinaryRowDecoder;
import org.mariadb.jdbc.codec.Codec;
import org.mariadb.jdbc.codec.RowDecoder;
import org.mariadb.jdbc.codec.TextRowDecoder;
import org.mariadb.jdbc.codec.list.*;
import org.mariadb.jdbc.message.server.ColumnDefinitionPacket;
import org.mariadb.jdbc.message.server.Completion;
import org.mariadb.jdbc.message.server.ErrorPacket;
import org.mariadb.jdbc.util.constants.ServerStatus;
import org.mariadb.jdbc.util.exceptions.ExceptionFactory;

public abstract class Result implements ResultSet, Completion {

  protected final int resultSetType;
  protected final ExceptionFactory exceptionFactory;
  protected final PacketReader reader;
  protected final Context context;
  private final int maxIndex;
  private final boolean closeOnCompletion;
  protected ColumnDefinitionPacket[] metadataList;
  protected RowDecoder row;
  protected int dataSize = 0;
  protected ReadableByteBuf[] data;
  protected boolean loaded;
  protected boolean outputParameter;
  protected int rowPointer = -1;
  protected boolean closed;
  protected Statement statement;
  protected long maxRows;
  private boolean forceAlias;
  private boolean traceEnable;

  public Result(
      org.mariadb.jdbc.Statement stmt,
      boolean binaryProtocol,
      long maxRows,
      ColumnDefinitionPacket[] metadataList,
      PacketReader reader,
      Context context,
      int resultSetType,
      boolean closeOnCompletion,
      boolean traceEnable) {
    this.maxRows = maxRows;
    this.statement = stmt;
    this.closeOnCompletion = closeOnCompletion;
    this.metadataList = metadataList;
    this.maxIndex = this.metadataList.length;
    this.reader = reader;
    this.exceptionFactory = context.getExceptionFactory();
    this.context = context;
    this.resultSetType = resultSetType;
    this.traceEnable = traceEnable;
    row =
        binaryProtocol
            ? new BinaryRowDecoder(this.maxIndex, metadataList, context.getConf())
            : new TextRowDecoder(this.maxIndex, metadataList, context.getConf());
  }

  public Result(ColumnDefinitionPacket[] metadataList, ReadableByteBuf[] data, Context context) {
    this.metadataList = metadataList;
    this.maxIndex = this.metadataList.length;
    this.reader = null;
    this.exceptionFactory = context.getExceptionFactory();
    this.context = context;
    this.data = data;
    this.dataSize = data.length;
    this.statement = null;
    this.resultSetType = TYPE_FORWARD_ONLY;
    this.closeOnCompletion = false;
    this.traceEnable = false;
    row = new TextRowDecoder(maxIndex, metadataList, context.getConf());
  }

  @SuppressWarnings("fallthrough")
  protected boolean readNext() throws SQLException, IOException {
    ReadableByteBuf buf = reader.readPacket(false, traceEnable);
    switch (buf.getByte()) {
      case (byte) 0xFF:
        loaded = true;
        ErrorPacket errorPacket = new ErrorPacket(buf, context);
        throw exceptionFactory.create(
            errorPacket.getMessage(), errorPacket.getSqlState(), errorPacket.getErrorCode());

      case (byte) 0xFE:
        if ((context.isEofDeprecated() && buf.readableBytes() < 0xffffff)
            || (!context.isEofDeprecated() && buf.readableBytes() < 8)) {

          buf.skip(); // skip header
          int serverStatus;
          int warnings;

          if (!context.isEofDeprecated()) {
            // EOF_Packet
            warnings = buf.readUnsignedShort();
            serverStatus = buf.readUnsignedShort();
          } else {
            // OK_Packet with a 0xFE header
            buf.skip(buf.readLengthNotNull()); // skip update count
            buf.skip(buf.readLengthNotNull()); // skip insert id
            serverStatus = buf.readUnsignedShort();
            warnings = buf.readUnsignedShort();
          }
          outputParameter = (serverStatus & ServerStatus.PS_OUT_PARAMETERS) != 0;
          context.setServerStatus(serverStatus);
          context.setWarning(warnings);
          loaded = true;
          return false;
        }

        // continue reading rows

      default:
        if (dataSize + 1 >= data.length) {
          growDataArray();
        }
        data[dataSize++] = buf;
    }
    return true;
  }

  @SuppressWarnings("fallthrough")
  protected void skipRemaining() throws SQLException, IOException {
    while (true) {
      ReadableByteBuf buf = reader.readPacket(true, traceEnable);
      switch (buf.getUnsignedByte()) {
        case 0xFF:
          loaded = true;
          ErrorPacket errorPacket = new ErrorPacket(buf, context);
          throw exceptionFactory.create(
              errorPacket.getMessage(), errorPacket.getSqlState(), errorPacket.getErrorCode());

        case 0xFE:
          if ((context.isEofDeprecated() && buf.readableBytes() < 0xffffff)
              || (!context.isEofDeprecated() && buf.readableBytes() < 8)) {

            buf.skip(); // skip header
            int serverStatus;
            int warnings;

            if (!context.isEofDeprecated()) {
              // EOF_Packet
              warnings = buf.readUnsignedShort();
              serverStatus = buf.readUnsignedShort();
            } else {
              // OK_Packet with a 0xFE header
              buf.skip(buf.readLengthNotNull()); // skip update count
              buf.skip(buf.readLengthNotNull()); // skip insert id
              serverStatus = buf.readUnsignedShort();
              warnings = buf.readUnsignedShort();
            }
            outputParameter = (serverStatus & ServerStatus.PS_OUT_PARAMETERS) != 0;
            context.setServerStatus(serverStatus);
            context.setWarning(warnings);
            loaded = true;
            return;
          }
      }
    }
  }

  /** Grow data array. */
  private void growDataArray() {
    int newCapacity = data.length + (data.length >> 1);
    data = Arrays.copyOf(data, newCapacity);
  }

  @Override
  public abstract boolean next() throws SQLException;

  public abstract boolean streaming();

  public abstract void fetchRemaining() throws SQLException;

  public boolean loaded() {
    return loaded;
  }

  public boolean isOutputParameter() {
    return outputParameter;
  }

  @Override
  public void close() throws SQLException {
    this.fetchRemaining();
    this.closed = true;
    if (closeOnCompletion) {
      statement.close();
    }
  }

  protected ReadableByteBuf getCurrentRowData() {
    return data[0];
  }

  protected void addRowData(ReadableByteBuf buf) {
    if (dataSize + 1 >= data.length) {
      growDataArray();
    }
    data[dataSize++] = buf;
  }

  protected void updateRowData(ReadableByteBuf rawData) {
    data[rowPointer] = rawData;
    row.setRow(rawData);
  }

  @Override
  public boolean wasNull() throws SQLException {
    return row.wasNull();
  }

  @Override
  public String getString(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, StringCodec.INSTANCE);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    Boolean b = row.getValue(columnIndex, BooleanCodec.INSTANCE);
    return (b == null) ? false : b;
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    Byte b = row.getValue(columnIndex, ByteCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    Short b = row.getValue(columnIndex, ShortCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    Integer b = row.getValue(columnIndex, IntCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    Long b = row.getValue(columnIndex, LongCodec.INSTANCE);
    return (b == null) ? 0L : b;
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    Float b = row.getValue(columnIndex, FloatCodec.INSTANCE);
    return (b == null) ? 0F : b;
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    Double b = row.getValue(columnIndex, DoubleCodec.INSTANCE);
    return (b == null) ? 0D : b;
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal d = row.getValue(columnIndex, BigDecimalCodec.INSTANCE);
    if (d == null) return d;
    return d.setScale(scale, BigDecimal.ROUND_HALF_DOWN);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, ByteArrayCodec.INSTANCE);
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, DateCodec.INSTANCE, null);
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, TimeCodec.INSTANCE, null);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, TimestampCodec.INSTANCE, null);
  }

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, StreamCodec.INSTANCE);
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, StreamCodec.INSTANCE);
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, StreamCodec.INSTANCE);
  }

  @Override
  public String getString(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, StringCodec.INSTANCE);
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    Boolean b = row.getValue(columnLabel, BooleanCodec.INSTANCE);
    return (b == null) ? false : b;
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    Byte b = row.getValue(columnLabel, ByteCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    Short b = row.getValue(columnLabel, ShortCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    Integer b = row.getValue(columnLabel, IntCodec.INSTANCE);
    return (b == null) ? 0 : b;
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    Long b = row.getValue(columnLabel, LongCodec.INSTANCE);
    return (b == null) ? 0L : b;
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    Float b = row.getValue(columnLabel, FloatCodec.INSTANCE);
    return (b == null) ? 0F : b;
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    Double b = row.getValue(columnLabel, DoubleCodec.INSTANCE);
    return (b == null) ? 0D : b;
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    BigDecimal d = row.getValue(columnLabel, BigDecimalCodec.INSTANCE);
    if (d == null) return d;
    return d.setScale(scale, BigDecimal.ROUND_HALF_DOWN);
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, ByteArrayCodec.INSTANCE);
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, DateCodec.INSTANCE, null);
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, TimeCodec.INSTANCE, null);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, TimestampCodec.INSTANCE, null);
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, StreamCodec.INSTANCE);
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, StreamCodec.INSTANCE);
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, StreamCodec.INSTANCE);
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    if (this.statement == null) {
      return null;
    }
    return this.statement.getWarnings();
  }

  @Override
  public void clearWarnings() throws SQLException {
    if (this.statement != null) {
      this.statement.clearWarnings();
    }
  }

  @Override
  public String getCursorName() throws SQLException {
    throw exceptionFactory.notSupported("Cursors are not supported");
  }

  @Override
  public ResultSetMetaData getMetaData() {
    return new ResultSetMetaData(
        exceptionFactory, metadataList, context.getConf(), forceAlias, false);
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    if (columnIndex < 1 || columnIndex > maxIndex) {
      throw new SQLException(
          String.format(
              "Wrong index position. Is %s but must be in 1-%s range", columnIndex, maxIndex));
    }
    Codec<?> defaultCodec = metadataList[columnIndex - 1].getDefaultCodec(context.getConf());
    return row.getValue(columnIndex, defaultCodec, null);
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(row.getIndex(columnLabel));
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    return row.getIndex(columnLabel);
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, ReaderCodec.INSTANCE);
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, ReaderCodec.INSTANCE);
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, BigDecimalCodec.INSTANCE);
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, BigDecimalCodec.INSTANCE);
  }

  protected void checkClose() throws SQLException {
    if (closed) {
      throw exceptionFactory.create("Operation not permit on a closed resultSet", "HY000");
    }
  }

  protected void checkNotForwardOnly() throws SQLException {
    if (resultSetType == ResultSet.TYPE_FORWARD_ONLY) {
      throw exceptionFactory.create("Operation not permit on TYPE_FORWARD_ONLY resultSet", "HY000");
    }
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkClose();
    return rowPointer == -1 && dataSize > 0;
  }

  @Override
  public abstract boolean isAfterLast() throws SQLException;

  @Override
  public abstract boolean isFirst() throws SQLException;

  @Override
  public abstract boolean isLast() throws SQLException;

  @Override
  public abstract void beforeFirst() throws SQLException;

  @Override
  public abstract void afterLast() throws SQLException;

  @Override
  public abstract boolean first() throws SQLException;

  @Override
  public abstract boolean last() throws SQLException;

  @Override
  public abstract int getRow() throws SQLException;

  @Override
  public abstract boolean absolute(int row) throws SQLException;

  @Override
  public abstract boolean relative(int rows) throws SQLException;

  @Override
  public abstract boolean previous() throws SQLException;

  @Override
  public int getFetchDirection() {
    return FETCH_UNKNOWN;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction == FETCH_REVERSE) {
      throw exceptionFactory.create(
          "Invalid operation. Allowed direction are ResultSet.FETCH_FORWARD and ResultSet.FETCH_UNKNOWN");
    }
  }

  @Override
  public int getType() {
    return resultSetType;
  }

  @Override
  public int getConcurrency() {
    return CONCUR_READ_ONLY;
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public boolean rowInserted() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void insertRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public Statement getStatement() throws SQLException {
    return statement;
  }

  public void setStatement(Statement stmt) throws SQLException {
    statement = stmt;
  }

  public void useAliasAsName() {
    for (ColumnDefinitionPacket packet : metadataList) {
      packet.useAliasAsName();
    }
    forceAlias = true;
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    throw exceptionFactory.notSupported(
        "Method ResultSet.getObject(int columnIndex, Map<String, Class<?>> map) not supported");
  }

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getRef not supported");
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, BlobCodec.INSTANCE);
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, ClobCodec.INSTANCE);
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getArray not supported");
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    throw exceptionFactory.notSupported(
        "Method ResultSet.getObject(String columnLabel, Map<String, Class<?>> map) not supported");
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getRef not supported");
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, BlobCodec.INSTANCE);
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, ClobCodec.INSTANCE);
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getArray not supported");
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    return row.getValue(columnIndex, DateCodec.INSTANCE, cal);
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return row.getValue(columnLabel, DateCodec.INSTANCE, cal);
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    return row.getValue(columnIndex, TimeCodec.INSTANCE, cal);
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return row.getValue(columnLabel, TimeCodec.INSTANCE, cal);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    return row.getValue(columnIndex, TimestampCodec.INSTANCE, cal);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return row.getValue(columnLabel, TimestampCodec.INSTANCE, cal);
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    String s = row.getValue(columnIndex, StringCodec.INSTANCE);
    if (s == null) return null;
    try {
      return new URL(s);
    } catch (MalformedURLException e) {
      throw exceptionFactory.create(String.format("Could not parse '%s' as URL", s));
    }
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    return getURL(row.getIndex(columnLabel));
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.updateRef not supported");
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.updateRef not supported");
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw exceptionFactory.notSupported("Array are not supported");
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw exceptionFactory.notSupported("Array are not supported");
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    throw exceptionFactory.notSupported("RowId are not supported");
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    throw exceptionFactory.notSupported("RowId are not supported");
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw exceptionFactory.notSupported("RowId are not supported");
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw exceptionFactory.notSupported("RowId are not supported");
  }

  @Override
  public int getHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public boolean isClosed() throws SQLException {
    return closed;
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    return (NClob) row.getValue(columnIndex, ClobCodec.INSTANCE);
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    return (NClob) row.getValue(columnLabel, ClobCodec.INSTANCE);
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getSQLXML not supported");
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw exceptionFactory.notSupported("Method ResultSet.getSQLXML not supported");
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, StringCodec.INSTANCE);
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, StringCodec.INSTANCE);
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    return row.getValue(columnIndex, ReaderCodec.INSTANCE);
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    return row.getValue(columnLabel, ReaderCodec.INSTANCE);
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    return row.getValue(columnIndex, type, null);
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(row.getIndex(columnLabel), type);
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (isWrapperFor(iface)) {
      return iface.cast(this);
    }
    throw new SQLException("The receiver is not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType)
      throws SQLException {
    throw exceptionFactory.notSupported("Not supported when using CONCUR_READ_ONLY concurrency");
  }
}
