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

package org.mariadb.jdbc.codec.list;

import java.io.IOException;
import java.sql.SQLDataException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.EnumSet;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.client.socket.PacketWriter;
import org.mariadb.jdbc.codec.Codec;
import org.mariadb.jdbc.codec.DataType;
import org.mariadb.jdbc.message.server.ColumnDefinitionPacket;

public class ZonedDateTimeCodec implements Codec<ZonedDateTime> {

  public static final ZonedDateTimeCodec INSTANCE = new ZonedDateTimeCodec();

  private static final EnumSet<DataType> COMPATIBLE_TYPES =
      EnumSet.of(
          DataType.DATETIME,
          DataType.DATE,
          DataType.TIMESTAMP,
          DataType.VARSTRING,
          DataType.VARCHAR,
          DataType.STRING,
          DataType.TIME);

  public String className() {
    return ZonedDateTime.class.getName();
  }

  public boolean canDecode(ColumnDefinitionPacket column, Class<?> type) {
    return COMPATIBLE_TYPES.contains(column.getType())
        && type.isAssignableFrom(ZonedDateTime.class);
  }

  public boolean canEncode(Object value) {
    return value instanceof ZonedDateTime;
  }

  @Override
  public ZonedDateTime decodeText(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar calParam)
      throws SQLDataException {
    LocalDateTime localDateTime =
        LocalDateTimeCodec.INSTANCE.decodeText(buf, length, column, calParam);
    if (localDateTime == null) return null;
    Calendar cal = calParam == null ? Calendar.getInstance() : calParam;
    return localDateTime.atZone(cal.getTimeZone().toZoneId());
  }

  @Override
  public ZonedDateTime decodeBinary(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar calParam)
      throws SQLDataException {
    LocalDateTime localDateTime =
        LocalDateTimeCodec.INSTANCE.decodeBinary(buf, length, column, calParam);
    if (localDateTime == null) return null;
    Calendar cal = calParam == null ? Calendar.getInstance() : calParam;
    return localDateTime.atZone(cal.getTimeZone().toZoneId());
  }

  @Override
  public void encodeText(
      PacketWriter encoder, Context context, Object val, Calendar cal, Long maxLen)
      throws IOException {
    ZonedDateTime zdt = (ZonedDateTime) val;
    encoder.writeByte('\'');
    encoder.writeAscii(
            zdt.format(
                    zdt.getNano() != 0
                ? LocalDateTimeCodec.TIMESTAMP_FORMAT
                : LocalDateTimeCodec.TIMESTAMP_FORMAT_NO_FRACTIONAL));
    encoder.writeByte('\'');
  }

  @Override
  public void encodeBinary(PacketWriter encoder, Context context, Object value, Calendar cal)
      throws IOException {
    ZonedDateTime zdt = (ZonedDateTime) value;
    int nano = zdt.getNano();
    if (nano > 0) {
      encoder.writeByte((byte) 11);
      encoder.writeShort((short) zdt.get(ChronoField.YEAR));
      encoder.writeByte(zdt.get(ChronoField.MONTH_OF_YEAR));
      encoder.writeByte(zdt.get(ChronoField.DAY_OF_MONTH));
      encoder.writeByte(zdt.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte(zdt.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte(zdt.get(ChronoField.SECOND_OF_MINUTE));
      encoder.writeInt(nano / 1000);
    } else {
      encoder.writeByte((byte) 7);
      encoder.writeShort((short) zdt.get(ChronoField.YEAR));
      encoder.writeByte(zdt.get(ChronoField.MONTH_OF_YEAR));
      encoder.writeByte(zdt.get(ChronoField.DAY_OF_MONTH));
      encoder.writeByte(zdt.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte(zdt.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte(zdt.get(ChronoField.SECOND_OF_MINUTE));
    }
  }

  public int getBinaryEncodeType() {
    return DataType.DATETIME.get();
  }
}
