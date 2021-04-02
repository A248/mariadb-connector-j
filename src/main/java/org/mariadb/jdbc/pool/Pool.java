/*
 *
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

package org.mariadb.jdbc.pool;

import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.Connection;
import org.mariadb.jdbc.Driver;
import org.mariadb.jdbc.util.log.Logger;
import org.mariadb.jdbc.util.log.Loggers;

public class Pool implements AutoCloseable, PoolMBean {

  private static final Logger logger = Loggers.getLogger(Pool.class);

  private static final int POOL_STATE_OK = 0;
  private static final int POOL_STATE_CLOSING = 1;

  private final AtomicInteger poolState = new AtomicInteger();

  private final Configuration conf;
  private final AtomicInteger pendingRequestNumber = new AtomicInteger();
  private final AtomicInteger totalConnection = new AtomicInteger();

  private final LinkedBlockingDeque<Connection> idleConnections;
  private final ThreadPoolExecutor connectionAppender;
  private final BlockingQueue<Runnable> connectionAppenderQueue;

  private final String poolTag;
  private final ScheduledThreadPoolExecutor poolExecutor;
  private final ScheduledFuture<?> scheduledFuture;

  private int maxIdleTime;

  /**
   * Create pool from configuration.
   *
   * @param conf configuration parser
   * @param poolIndex pool index to permit distinction of thread name
   * @param poolExecutor pools common executor
   */
  public Pool(Configuration conf, int poolIndex, ScheduledThreadPoolExecutor poolExecutor) {

    this.conf = conf;
    this.maxIdleTime = conf.maxIdleTime() == null ? 600 : conf.maxIdleTime().intValue();
    poolTag = generatePoolTag(poolIndex);

    // one thread to add new connection to pool.
    connectionAppenderQueue = new ArrayBlockingQueue<>(conf.maxPoolSize());
    connectionAppender =
        new ThreadPoolExecutor(
            1,
            1,
            10,
            TimeUnit.SECONDS,
            connectionAppenderQueue,
            new PoolThreadFactory(poolTag + "-appender"));
    connectionAppender.allowCoreThreadTimeOut(true);
    // create workers, since driver only interact with queue after that (i.e. not using .execute() )
    connectionAppender.prestartCoreThread();

    idleConnections = new LinkedBlockingDeque<>();
    int minDelay =
        Integer.parseInt(conf.nonMappedOptions().getProperty("testMinRemovalDelay", "30"));
    int scheduleDelay = Math.min(minDelay, maxIdleTime / 2);
    this.poolExecutor = poolExecutor;
    scheduledFuture =
        poolExecutor.scheduleAtFixedRate(
            this::removeIdleTimeoutConnection, scheduleDelay, scheduleDelay, TimeUnit.SECONDS);

    if (conf.registerJmxPool()) {
      try {
        registerJmx();
      } catch (Exception ex) {
        logger.error("pool " + poolTag + " not registered due to exception : " + ex.getMessage());
      }
    }

    // create minimal connection in pool
    try {
      for (int i = 0; i < conf.minPoolSize(); i++) {
        addConnection();
      }
    } catch (SQLException sqle) {
      logger.error("error initializing pool connection", sqle);
    }
  }

  /**
   * Add new connection if needed. Only one thread create new connection, so new connection request
   * will wait to newly created connection or for a released connection.
   */
  private void addConnectionRequest() {
    if (totalConnection.get() < conf.maxPoolSize() && poolState.get() == POOL_STATE_OK) {

      // ensure to have one worker if was timeout
      connectionAppender.prestartCoreThread();
      connectionAppenderQueue.offer(
          () -> {
            if ((totalConnection.get() < conf.minPoolSize() || pendingRequestNumber.get() > 0)
                && totalConnection.get() < conf.maxPoolSize()) {
              try {
                addConnection();
              } catch (SQLException sqle) {
                // eat
              }
            }
          });
    }
  }

  /**
   * Removing idle connection. Close them and recreate connection to reach minimal number of
   * connection.
   */
  private void removeIdleTimeoutConnection() {

    // descending iterator since first from queue are the first to be used
    Iterator<Connection> iterator = idleConnections.descendingIterator();

    Connection item;

    while (iterator.hasNext()) {
      item = iterator.next();

      long idleTime = System.nanoTime() - item.getLastUsed();
      boolean timedOut = idleTime > TimeUnit.SECONDS.toNanos(maxIdleTime);

      boolean shouldBeReleased = false;

      if (item.getHostAddress().getWaitTimeout() > 0) {

        // idle time is reaching server @@wait_timeout
        if (idleTime > TimeUnit.SECONDS.toNanos(item.getHostAddress().getWaitTimeout() - 45)) {
          shouldBeReleased = true;
        }

        //  idle has reach option maxIdleTime value and pool has more connections than minPoolSiz
        if (timedOut && totalConnection.get() > conf.minPoolSize()) {
          shouldBeReleased = true;
        }

      } else if (timedOut) {
        shouldBeReleased = true;
      }

      if (shouldBeReleased && idleConnections.remove(item)) {

        totalConnection.decrementAndGet();
        silentCloseConnection(item);
        addConnectionRequest();
        if (logger.isDebugEnabled()) {
          logger.debug(
              "pool {} connection removed due to inactivity (total:{}, active:{}, pending:{})",
              poolTag,
              totalConnection.get(),
              getActiveConnections(),
              pendingRequestNumber.get());
        }
      }
    }
  }

  /**
   * Create new connection.
   *
   * @throws SQLException if connection creation failed
   */
  private void addConnection() throws SQLException {

    // create new connection
    Connection connection = Driver.connect(conf);
    connection.pooled();
    connection.addConnectionEventListener(
        new ConnectionEventListener() {

          @Override
          public void connectionClosed(ConnectionEvent event) {
            Connection item = (Connection) event.getSource();
            if (poolState.get() == POOL_STATE_OK) {
              try {
                if (!idleConnections.contains(item)) {
                  item.reset();
                  idleConnections.addFirst(item);
                }
              } catch (SQLException sqle) {

                // sql exception during reset, removing connection from pool
                totalConnection.decrementAndGet();
                silentCloseConnection(item);
                logger.debug("connection removed from pool {} due to error during reset", poolTag);
              }
            } else {
              // pool is closed, should then not be render to pool, but closed.
              try {
                item.unPooled();
                item.close();
              } catch (SQLException sqle) {
                // eat
              }
              totalConnection.decrementAndGet();
            }
          }

          @Override
          public void connectionErrorOccurred(ConnectionEvent event) {

            Connection item = ((Connection) event.getSource());
            if (idleConnections.remove(item)) {
              totalConnection.decrementAndGet();
            }
            silentCloseConnection(item);
            addConnectionRequest();
            logger.debug(
                "connection {} removed from pool {} due to having throw a Connection exception (total:{}, active:{}, pending:{})",
                item.getThreadId(),
                poolTag,
                totalConnection.get(),
                getActiveConnections(),
                pendingRequestNumber.get());
          }
        });
    if (poolState.get() == POOL_STATE_OK
        && totalConnection.incrementAndGet() <= conf.maxPoolSize()) {
      idleConnections.addFirst(connection);

      if (logger.isDebugEnabled()) {
        logger.debug(
            "pool {} new physical connection created (total:{}, active:{}, pending:{})",
            poolTag,
            totalConnection.get(),
            getActiveConnections(),
            pendingRequestNumber.get());
      }
      return;
    }

    silentCloseConnection(connection);
  }

  private Connection getIdleConnection() throws InterruptedException {
    return getIdleConnection(0, TimeUnit.NANOSECONDS);
  }

  /**
   * Get an existing idle connection in pool.
   *
   * @return an IDLE connection.
   */
  private Connection getIdleConnection(long timeout, TimeUnit timeUnit)
      throws InterruptedException {

    while (true) {
      Connection connection =
          (timeout == 0)
              ? idleConnections.pollFirst()
              : idleConnections.pollFirst(timeout, timeUnit);

      if (connection != null) {
        try {
          if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connection.getLastUsed())
              > conf.poolValidMinDelay()) {

            // validate connection
            if (connection.isValid(10)) { // 10 seconds timeout
              connection.lastUsedToNow();
              connection.pooled();
              return connection;
            }

          } else {

            // connection has been retrieved recently -> skip connection validation
            connection.lastUsedToNow();
            connection.pooled();
            return connection;
          }

        } catch (SQLException sqle) {
          // eat
        }

        totalConnection.decrementAndGet();

        // validation failed
        silentAbortConnection(connection);
        addConnectionRequest();
        if (logger.isDebugEnabled()) {
          logger.debug(
              "pool {} connection removed from pool due to failed validation (total:{}, active:{}, pending:{})",
              poolTag,
              totalConnection.get(),
              getActiveConnections(),
              pendingRequestNumber.get());
        }
        continue;
      }

      return null;
    }
  }

  private void silentCloseConnection(Connection item) {
    try {
      item.unPooled();
      item.close();
    } catch (SQLException ex) {
      // eat exception
    }
  }

  private void silentAbortConnection(Connection item) {
    try {
      item.abort(poolExecutor);
    } catch (SQLException ex) {
      // eat exception
    }
  }

  /**
   * Retrieve new connection. If possible return idle connection, if not, stack connection query,
   * ask for a connection creation, and loop until a connection become idle / a new connection is
   * created.
   *
   * @return a connection object
   * @throws SQLException if no connection is created when reaching timeout (connectTimeout option)
   */
  public Connection getConnection() throws SQLException {

    pendingRequestNumber.incrementAndGet();

    Connection connection;

    try {

      // try to get Idle connection if any (with a very small timeout)
      if ((connection =
              getIdleConnection(totalConnection.get() > 4 ? 0 : 50, TimeUnit.MICROSECONDS))
          != null) {
        return connection;
      }

      // ask for new connection creation if max is not reached
      addConnectionRequest();

      // try to create new connection if semaphore permit it
      if ((connection =
              getIdleConnection(
                  TimeUnit.MILLISECONDS.toNanos(conf.connectTimeout()), TimeUnit.NANOSECONDS))
          != null) {
        return connection;
      }

      throw new SQLException(
          String.format(
              "No connection available within the specified time (option 'connectTimeout': %s ms)",
              NumberFormat.getInstance().format(conf.connectTimeout())));

    } catch (InterruptedException interrupted) {
      throw new SQLException("Thread was interrupted", "70100", interrupted);
    } finally {
      pendingRequestNumber.decrementAndGet();
    }
  }

  /**
   * Get new connection from pool if user and password correspond to pool. If username and password
   * are different from pool, will return a dedicated connection.
   *
   * @param username username
   * @param password password
   * @return connection
   * @throws SQLException if any error occur during connection
   */
  public Connection getConnection(String username, String password) throws SQLException {

    try {

      if ((conf.user() != null ? conf.user().equals(username) : username == null)
          && (conf.password() != null ? conf.password().equals(password) : password == null)) {
        return getConnection();
      }

      Configuration tmpConf = conf.clone(username, password);
      return Driver.connect(tmpConf);

    } catch (CloneNotSupportedException cloneException) {
      // cannot occur
      throw new SQLException(
          "Error getting connection, parameters cannot be cloned", cloneException);
    }
  }

  private String generatePoolTag(int poolIndex) {
    if (conf.poolName() == null) {
      return "MariaDB-pool";
    }
    return conf.poolName() + "-" + poolIndex;
  }

  public Configuration getConf() {
    return conf;
  }

  /**
   * Close pool and underlying connections.
   *
   * @throws Exception if interrupted
   */
  public void close() throws InterruptedException {
    synchronized (this) {
      Pools.remove(this);
      poolState.set(POOL_STATE_CLOSING);
      pendingRequestNumber.set(0);

      scheduledFuture.cancel(false);
      connectionAppender.shutdown();

      try {
        connectionAppender.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException i) {
        // eat
      }

      if (logger.isInfoEnabled()) {
        logger.info(
            "closing pool {} (total:{}, active:{}, pending:{})",
            poolTag,
            totalConnection.get(),
            getActiveConnections(),
            pendingRequestNumber.get());
      }

      ExecutorService connectionRemover =
          new ThreadPoolExecutor(
              totalConnection.get(),
              conf.maxPoolSize(),
              10,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(conf.maxPoolSize()),
              new PoolThreadFactory(poolTag + "-destroyer"));

      // loop for up to 10 seconds to close not used connection
      long start = System.nanoTime();
      do {
        closeAll(connectionRemover, idleConnections);
        if (totalConnection.get() > 0) {
          Thread.sleep(0, 10_00);
        }
      } while (totalConnection.get() > 0
          && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);

      // after having wait for 10 seconds, force removal, even if used connections
      if (totalConnection.get() > 0 || idleConnections.isEmpty()) {
        closeAll(connectionRemover, idleConnections);
      }

      connectionRemover.shutdown();
      try {
        unRegisterJmx();
      } catch (Exception exception) {
        // eat
      }
      connectionRemover.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private void closeAll(ExecutorService connectionRemover, Collection<Connection> collection) {
    synchronized (collection) { // synchronized mandatory to iterate Collections.synchronizedList()
      for (Connection item : collection) {
        collection.remove(item);
        totalConnection.decrementAndGet();
        try {
          item.abort(connectionRemover);
        } catch (SQLException ex) {
          // eat exception
        }
      }
    }
  }

  public String getPoolTag() {
    return poolTag;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }

    Pool pool = (Pool) obj;

    return poolTag.equals(pool.poolTag);
  }

  @Override
  public int hashCode() {
    return poolTag.hashCode();
  }

  @Override
  public long getActiveConnections() {
    return totalConnection.get() - idleConnections.size();
  }

  @Override
  public long getTotalConnections() {
    return totalConnection.get();
  }

  @Override
  public long getIdleConnections() {
    return idleConnections.size();
  }

  public long getConnectionRequests() {
    return pendingRequestNumber.get();
  }

  private void registerJmx() throws Exception {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    String jmxName = poolTag.replace(":", "_");
    ObjectName name = new ObjectName("org.mariadb.jdbc.pool:type=" + jmxName);

    if (!mbs.isRegistered(name)) {
      mbs.registerMBean(this, name);
    }
  }

  private void unRegisterJmx() throws Exception {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    String jmxName = poolTag.replace(":", "_");
    ObjectName name = new ObjectName("org.mariadb.jdbc.pool:type=" + jmxName);

    if (mbs.isRegistered(name)) {
      mbs.unregisterMBean(name);
    }
  }

  /**
   * For testing purpose only.
   *
   * @return current thread id's
   */
  public List<Long> testGetConnectionIdleThreadIds() {
    List<Long> threadIds = new ArrayList<>();
    for (Connection pooledConnection : idleConnections) {
      threadIds.add(pooledConnection.getThreadId());
    }
    return threadIds;
  }
}
