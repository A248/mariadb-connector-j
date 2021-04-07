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

package org.mariadb.jdbc;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.*;
import org.mariadb.jdbc.client.Client;
import org.mariadb.jdbc.client.ClientImpl;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.message.client.ChangeDbPacket;
import org.mariadb.jdbc.message.client.PingPacket;
import org.mariadb.jdbc.message.client.QueryPacket;
import org.mariadb.jdbc.message.client.ResetPacket;
import org.mariadb.jdbc.util.NativeSql;
import org.mariadb.jdbc.util.constants.Capabilities;
import org.mariadb.jdbc.util.constants.ConnectionState;
import org.mariadb.jdbc.util.constants.ServerStatus;
import org.mariadb.jdbc.util.exceptions.ExceptionFactory;
import org.mariadb.jdbc.util.log.Logger;
import org.mariadb.jdbc.util.log.Loggers;

public class Connection implements java.sql.Connection {

  private static final Logger logger = Loggers.getLogger(Connection.class);
  private static final Pattern CALLABLE_STATEMENT_PATTERN =
      Pattern.compile(
          "^(\\s*\\{)?\\s*((\\?\\s*=)?(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*"
              + "call(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*\\s*((((`[^`]+`)|([^`\\}]+))\\.)?"
              + "((`[^`]+`)|([^`\\}\\(]+)))\\s*(\\(.*\\))?(\\s*\\/\\*([^\\*]|\\*[^\\/])*\\*\\/)*"
              + "\\s*(#.*)?)\\s*(\\}\\s*)?$",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private final ReentrantLock lock;
  private final List<ConnectionEventListener> connectionEventListeners = new ArrayList<>();
  private final List<StatementEventListener> statementEventListeners = new ArrayList<>();
  private final Configuration conf;
  private ExceptionFactory exceptionFactory;
  private final Client client;
  private final Properties clientInfo = new Properties();
  private int lowercaseTableNames = -1;
  private final AtomicInteger savepointId = new AtomicInteger();
  private boolean readOnly;
  private final boolean canUseServerTimeout;
  private final boolean canUseServerMaxRows;
  private final int defaultFetchSize;
  private MariaDbPoolConnection poolConnection;

  public Connection(Configuration conf, ReentrantLock lock, Client client) throws SQLException {
    this.conf = conf;
    this.lock = lock;
    this.exceptionFactory = client.getExceptionFactory().setConnection(this);
    this.client = client;
    Context context = this.client.getContext();
    this.canUseServerTimeout =
        context.getVersion().isMariaDBServer()
            && context.getVersion().versionGreaterOrEqual(10, 1, 2);
    this.canUseServerMaxRows =
        context.getVersion().isMariaDBServer()
            && context.getVersion().versionGreaterOrEqual(10, 3, 0);
    this.defaultFetchSize = context.getConf().defaultFetchSize();
  }

  public void setPoolConnection(MariaDbPoolConnection poolConnection) {
    this.poolConnection = poolConnection;
    this.exceptionFactory = exceptionFactory.setPoolConnection(poolConnection);
  }

  /**
   * Cancels the current query - clones the current protocol and executes a query using the new
   * connection.
   *
   * @throws SQLException never thrown
   */
  public void cancelCurrentQuery() throws SQLException {
    try (Client cli = new ClientImpl(conf, client.getHostAddress(), new ReentrantLock(), true)) {
      cli.execute(new QueryPacket("KILL QUERY " + client.getContext().getThreadId()));
    }
  }

  @Override
  public Statement createStatement() {
    return new Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        Statement.RETURN_GENERATED_KEYS,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareInternal(
        sql,
        Statement.NO_GENERATED_KEYS,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        conf.useServerPrepStmts());
  }

  public PreparedStatement prepareInternal(
      String sql,
      int autoGeneratedKeys,
      int resultSetType,
      int resultSetConcurrency,
      boolean useBinary)
      throws SQLException {
    checkNotClosed();
    if (useBinary) {
      return new ServerPreparedStatement(
          NativeSql.parse(sql, client.getContext()),
          this,
          lock,
          canUseServerTimeout,
          canUseServerMaxRows,
          autoGeneratedKeys,
          resultSetType,
          resultSetConcurrency,
          defaultFetchSize);
    }
    return new ClientPreparedStatement(
        NativeSql.parse(sql, client.getContext()),
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        autoGeneratedKeys,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    return prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    return NativeSql.parse(sql, client.getContext());
  }

  @Override
  public boolean getAutoCommit() {
    return (client.getContext().getServerStatus() & ServerStatus.AUTOCOMMIT) > 0;
  }

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    if (autoCommit == getAutoCommit()) {
      return;
    }
    lock.lock();
    try {
      getContext().addStateFlag(ConnectionState.STATE_AUTOCOMMIT);
      client.execute(new QueryPacket("set autocommit=" + ((autoCommit) ? "1" : "0")));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void commit() throws SQLException {
    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        client.execute(new QueryPacket("COMMIT"));
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void rollback() throws SQLException {
    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        client.execute(new QueryPacket("ROLLBACK"));
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws SQLException {
    if (poolConnection != null) {
      MariaDbPoolConnection poolConnection = this.poolConnection;
      poolConnection.close();
      return;
    }
    client.close();
  }

  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  public Context getContext() {
    return client.getContext();
  }

  /**
   * Are table case sensitive or not . Default Value: 0 (Unix), 1 (Windows), 2 (Mac OS X). If set to
   * 0 (the default on Unix-based systems), table names and aliases and database names are compared
   * in a case-sensitive manner. If set to 1 (the default on Windows), names are stored in lowercase
   * and not compared in a case-sensitive manner. If set to 2 (the default on Mac OS X), names are
   * stored as declared, but compared in lowercase.
   *
   * @return int value.
   * @throws SQLException if a connection error occur
   */
  public int getLowercaseTableNames() throws SQLException {
    if (lowercaseTableNames == -1) {
      try (java.sql.Statement st = createStatement()) {
        try (ResultSet rs = st.executeQuery("select @@lower_case_table_names")) {
          rs.next();
          lowercaseTableNames = rs.getInt(1);
        }
      }
    }
    return lowercaseTableNames;
  }

  @Override
  public DatabaseMetaData getMetaData() {
    return new DatabaseMetaData(this, this.conf);
  }

  @Override
  public boolean isReadOnly() {
    return this.readOnly;
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    lock.lock();
    try {
      if (this.readOnly != readOnly) {
        client.setReadOnly(readOnly);
      }
      this.readOnly = readOnly;
      getContext().addStateFlag(ConnectionState.STATE_READ_ONLY);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String getCatalog() throws SQLException {

    if ((client.getContext().getServerCapabilities() & Capabilities.CLIENT_SESSION_TRACK) != 0) {
      // client session track return empty value, not null value. Java require sending null if empty
      String db = client.getContext().getDatabase();
      return (db != null && db.isEmpty()) ? null : db;
    }

    Statement stmt = createStatement();
    ResultSet rs = stmt.executeQuery("select database()");
    rs.next();
    client.getContext().setDatabase(rs.getString(1));
    return client.getContext().getDatabase();
  }

  @Override
  public void setCatalog(String catalog) throws SQLException {
    if ((client.getContext().getServerCapabilities() & Capabilities.CLIENT_SESSION_TRACK) != 0
        && catalog == client.getContext().getDatabase()) {
      return;
    }
    lock.lock();
    try {
      getContext().addStateFlag(ConnectionState.STATE_DATABASE);
      client.execute(new ChangeDbPacket(catalog));
      client.getContext().setDatabase(catalog);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getTransactionIsolation() throws SQLException {

    String sql = "SELECT @@tx_isolation";

    if (!client.getContext().getVersion().isMariaDBServer()) {
      if ((client.getContext().getVersion().getMajorVersion() >= 8
              && client.getContext().getVersion().versionGreaterOrEqual(8, 0, 3))
          || (client.getContext().getVersion().getMajorVersion() < 8
              && client.getContext().getVersion().versionGreaterOrEqual(5, 7, 20))) {
        sql = "SELECT @@transaction_isolation";
      }
    }

    ResultSet rs = createStatement().executeQuery(sql);
    if (rs.next()) {
      final String response = rs.getString(1);
      switch (response) {
        case "REPEATABLE-READ":
          return java.sql.Connection.TRANSACTION_REPEATABLE_READ;

        case "READ-UNCOMMITTED":
          return java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;

        case "READ-COMMITTED":
          return java.sql.Connection.TRANSACTION_READ_COMMITTED;

        case "SERIALIZABLE":
          return java.sql.Connection.TRANSACTION_SERIALIZABLE;

        default:
          throw exceptionFactory.create(
              String.format(
                  "Could not get transaction isolation level: Invalid value \"%s\"", response));
      }
    }
    throw exceptionFactory.create("Failed to retrieve transaction isolation");
  }

  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    String query = "SET SESSION TRANSACTION ISOLATION LEVEL";
    switch (level) {
      case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
        query += " READ UNCOMMITTED";
        break;
      case java.sql.Connection.TRANSACTION_READ_COMMITTED:
        query += " READ COMMITTED";
        break;
      case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
        query += " REPEATABLE READ";
        break;
      case java.sql.Connection.TRANSACTION_SERIALIZABLE:
        query += " SERIALIZABLE";
        break;
      default:
        throw new SQLException("Unsupported transaction isolation level");
    }
    lock.lock();
    try {
      checkNotClosed();
      getContext().addStateFlag(ConnectionState.STATE_TRANSACTION_ISOLATION);
      client.getContext().setTransactionIsolationLevel(level);
      client.execute(new QueryPacket(query));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkNotClosed();
    if (client.getContext().getWarning() == 0) {
      return null;
    }

    SQLWarning last = null;
    SQLWarning first = null;

    try (Statement st = this.createStatement()) {
      try (ResultSet rs = st.executeQuery("show warnings")) {
        // returned result set has 'level', 'code' and 'message' columns, in this order.
        while (rs.next()) {
          int code = rs.getInt(2);
          String message = rs.getString(3);
          SQLWarning warning = new SQLWarning(message, null, code);
          if (first == null) {
            first = warning;
          } else {
            last.setNextWarning(warning);
          }
          last = warning;
        }
      }
    }
    return first;
  }

  @Override
  public void clearWarnings() {
    client.getContext().setWarning(0);
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkNotClosed();
    return new Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        Statement.RETURN_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return prepareInternal(
        sql,
        Statement.RETURN_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        conf.useServerPrepStmts());
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkNotClosed();
    Matcher matcher = CALLABLE_STATEMENT_PATTERN.matcher(sql);
    if (!matcher.matches()) {
      throw new SQLSyntaxErrorException(
          "invalid callable syntax. must be like {[?=]call <procedure/function name>[(?,?, ...)]}\n but was : "
              + sql);
    }

    String query = NativeSql.parse(matcher.group(2), client.getContext());

    boolean isFunction = (matcher.group(3) != null);
    String databaseAndProcedure = matcher.group(8);
    String database = matcher.group(10);
    String procedureName = matcher.group(13);
    String arguments = matcher.group(16);
    if (database == null) {
      database = getCatalog();
    }

    if (isFunction) {
      return new FunctionStatement(
          this,
          database,
          databaseAndProcedure,
          (arguments == null) ? "()" : arguments,
          lock,
          canUseServerTimeout,
          canUseServerMaxRows,
          resultSetType,
          resultSetConcurrency);
    } else {
      return new ProcedureStatement(
          this,
          query,
          database,
          procedureName,
          lock,
          canUseServerTimeout,
          canUseServerMaxRows,
          resultSetType,
          resultSetConcurrency);
    }
  }

  @Override
  public Map<String, Class<?>> getTypeMap() {
    return new HashMap<>();
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    throw exceptionFactory.notSupported("TypeMap are not supported");
  }

  @Override
  public int getHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public void setHoldability(int holdability) {
    // not supported
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    Savepoint savepoint = new Savepoint(savepointId.incrementAndGet());
    client.execute(new QueryPacket("SAVEPOINT `" + savepoint.rawValue() + "`"));
    return savepoint;
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    Savepoint savepoint = new Savepoint(name.replace("`", "``"));
    client.execute(new QueryPacket("SAVEPOINT `" + savepoint.rawValue() + "`"));
    return savepoint;
  }

  @Override
  public void rollback(java.sql.Savepoint savepoint) throws SQLException {
    checkNotClosed();
    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        if (savepoint instanceof Connection.Savepoint) {
          client.execute(
              new QueryPacket(
                  "ROLLBACK TO SAVEPOINT `" + ((Connection.Savepoint) savepoint).rawValue() + "`"));
        } else {
          throw exceptionFactory.create("Unknown savepoint type");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
    checkNotClosed();
    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        if (savepoint instanceof Connection.Savepoint) {
          client.execute(
              new QueryPacket(
                  "RELEASE SAVEPOINT `" + ((Connection.Savepoint) savepoint).rawValue() + "`"));
        } else {
          throw exceptionFactory.create("Unknown savepoint type");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Statement createStatement(
      int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    checkNotClosed();
    return new Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        Statement.NO_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    return prepareStatement(sql, resultSetType, resultSetConcurrency);
  }

  @Override
  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    return prepareCall(sql, resultSetType, resultSetConcurrency);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    return prepareInternal(
        sql,
        autoGeneratedKeys,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        conf.useServerPrepStmts());
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public Clob createClob() {
    return new MariaDbClob();
  }

  @Override
  public Blob createBlob() {
    return new MariaDbBlob();
  }

  @Override
  public NClob createNClob() {
    return new MariaDbClob();
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    throw exceptionFactory.notSupported("SQLXML type is not supported");
  }

  private void checkNotClosed() throws SQLException {
    if (client.isClosed()) {
      throw exceptionFactory.create("Connection is closed", "08000", 1220);
    }
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw exceptionFactory.create("the value supplied for timeout is negative");
    }
    lock.lock();
    try {
      client.execute(PingPacket.INSTANCE);
      return true;
    } catch (SQLException sqle) {
      return false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void setClientInfo(String name, String value) {
    clientInfo.put(name, value);
  }

  @Override
  public String getClientInfo(String name) {
    return (String) clientInfo.get(name);
  }

  @Override
  public Properties getClientInfo() {
    return clientInfo;
  }

  @Override
  public void setClientInfo(Properties properties) {
    for (Map.Entry<?, ?> entry : properties.entrySet()) {
      clientInfo.put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    throw exceptionFactory.notSupported("Array type is not supported");
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    throw exceptionFactory.notSupported("Struct type is not supported");
  }

  @Override
  public String getSchema() {
    // We support only catalog
    return null;
  }

  @Override
  public void setSchema(String schema) {
    // We support only catalog, and JDBC indicate "If the driver does not support schemas, it will
    // silently ignore this request."
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    client.abort(executor);
  }

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    if (this.isClosed()) {
      throw exceptionFactory.create(
          "Connection.setNetworkTimeout cannot be called on a closed connection");
    }
    if (milliseconds < 0) {
      throw exceptionFactory.create(
          "Connection.setNetworkTimeout cannot be called with a negative timeout");
    }
    SQLPermission sqlPermission = new SQLPermission("setNetworkTimeout");
    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager != null) {
      securityManager.checkPermission(sqlPermission);
    }
    getContext().addStateFlag(ConnectionState.STATE_NETWORK_TIMEOUT);

    lock.lock();
    try {
      client.setSocketTimeout(milliseconds);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getNetworkTimeout() {
    return client.getSocketTimeout();
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

  public int getWaitTimeout() {
    return client.getWaitTimeout();
  }

  public Client getClient() {
    return client;
  }

  /** Internal Savepoint implementation */
  class Savepoint implements java.sql.Savepoint {

    private final String name;
    private final Integer id;

    public Savepoint(final String name) {
      this.name = name;
      this.id = null;
    }

    public Savepoint(final int savepointId) {
      this.id = savepointId;
      this.name = null;
    }

    /**
     * Retrieves the generated ID for the savepoint that this <code>Savepoint</code> object
     * represents.
     *
     * @return the numeric ID of this savepoint
     */
    public int getSavepointId() throws SQLException {
      if (name != null) {
        throw exceptionFactory.create("Cannot retrieve savepoint id of a named savepoint");
      }
      return id;
    }

    /**
     * Retrieves the name of the savepoint that this <code>Savepoint</code> object represents.
     *
     * @return the name of this savepoint
     */
    public String getSavepointName() throws SQLException {
      if (id != null) {
        throw exceptionFactory.create("Cannot retrieve savepoint name of an unnamed savepoint");
      }
      return name;
    }

    public String rawValue() {
      if (id != null) {
        return "_jid_" + id;
      }
      return name;
    }
  }

  /**
   * Reset connection set has it was after creating a "fresh" new connection.
   * defaultTransactionIsolation must have been initialized.
   *
   * <p>BUT : - session variable state are reset only if option useResetConnection is set and - if
   * using the option "useServerPrepStmts", PREPARE statement are still prepared
   *
   * @throws SQLException if resetting operation failed
   */
  public void reset() throws SQLException {
    // COM_RESET_CONNECTION exist since mysql 5.7.3 and mariadb 10.2.4
    // but not possible to use it with mysql waiting for https://bugs.mysql.com/bug.php?id=97633
    // correction.
    // and mariadb only since https://jira.mariadb.org/browse/MDEV-18281
    boolean useComReset =
        conf.useResetConnection()
            && getContext().getVersion().isMariaDBServer()
            && (getContext().getVersion().versionGreaterOrEqual(10, 3, 13)
                || (getContext().getVersion().getMajorVersion() == 10
                    && getContext().getVersion().getMinorVersion() == 2
                    && getContext().getVersion().versionGreaterOrEqual(10, 2, 22)));

    if (useComReset) {
      client.execute(ResetPacket.INSTANCE);
    }

    // in transaction => rollback
    if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
      client.execute(new QueryPacket("ROLLBACK"));
    }

    int stateFlag = getContext().getStateFlag();
    if (stateFlag != 0) {
      try {
        if ((stateFlag & ConnectionState.STATE_NETWORK_TIMEOUT) != 0) {
          setNetworkTimeout(null, conf.socketTimeout());
        }
        if ((stateFlag & ConnectionState.STATE_AUTOCOMMIT) != 0) {
          setAutoCommit(conf.autocommit());
        }
        if ((stateFlag & ConnectionState.STATE_DATABASE) != 0) {
          setCatalog(conf.database());
        }
        if ((stateFlag & ConnectionState.STATE_READ_ONLY) != 0) {
          setReadOnly(false); // default to master connection
        }
        if (!useComReset && (stateFlag & ConnectionState.STATE_TRANSACTION_ISOLATION) != 0) {
          setTransactionIsolation(conf.transactionIsolation().getLevel());
        }
      } catch (SQLException sqle) {
        throw exceptionFactory.create("error resetting connection");
      }
    }

    client.reset();

    clearWarnings();
  }

  public long getThreadId() {
    return client.getContext().getThreadId();
  }

  public void fireStatementClosed(PreparedStatement prep) {
    if (poolConnection != null) {
      poolConnection.fireStatementClosed(prep);
    }
  }

  protected ExceptionFactory getExceptionFactory() {
    return exceptionFactory;
  }
}
