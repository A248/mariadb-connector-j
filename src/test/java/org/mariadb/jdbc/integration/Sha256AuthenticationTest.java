package org.mariadb.jdbc.integration;

import java.io.File;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.mariadb.jdbc.Common;

public class Sha256AuthenticationTest extends Common {

  private static String rsaPublicKey;
  private static boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

  @AfterAll
  public static void drop() throws SQLException {
    org.mariadb.jdbc.Statement stmt = sharedConn.createStatement();
    stmt.execute("DROP USER IF EXISTS 'cachingSha256User'@'%'");
    stmt.execute("DROP USER IF EXISTS 'cachingSha256User2'@'%'");
  }

  @BeforeAll
  public static void init() throws Exception {
    Assumptions.assumeTrue(!isMariaDBServer() && minVersion(8, 0, 0));
    drop();
    Statement stmt = sharedConn.createStatement();
    rsaPublicKey = System.getProperty("rsaPublicKey");
    if (rsaPublicKey == null ) {
      ResultSet rs = stmt.executeQuery("SELECT @@caching_sha2_password_public_key_path");
      rs.next();
      rsaPublicKey = rs.getString(1);
      if (rsaPublicKey != null) {
        File f = new File(rsaPublicKey);
        if (!f.exists()) {
          f = new File("../../ssl/public.key");
          if (f.exists()) {
            rsaPublicKey = f.getCanonicalPath();
          }
        }
      }
    }

    stmt.execute(
        "CREATE USER 'cachingSha256User'@'%' IDENTIFIED WITH caching_sha2_password BY 'password'");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User'@'%'");
    stmt.execute(
            "CREATE USER 'cachingSha256User2'@'%' IDENTIFIED WITH caching_sha2_password BY ''");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User2'@'%'");
    stmt.execute("FLUSH PRIVILEGES");
  }

  @Test
  public void cachingSha256Empty() throws Exception {
    Assumptions.assumeTrue(
            !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con = createCon("user=cachingSha256User2")) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTestWithServerRsaKey() throws Exception {
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con =
        createCon(
            "user=cachingSha256User&password=password&serverRsaPublicKeyFile=" + rsaPublicKey)) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTestWithoutServerRsaKey() throws Exception {
    Assumptions.assumeTrue(!isWindows && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con =
        createCon("user=cachingSha256User&password=password&allowPublicKeyRetrieval")) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTestException() throws Exception {
    Assumptions.assumeTrue(!isMariaDBServer() && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache

    assertThrowsContains(
        SQLException.class,
        () -> createCon("user=cachingSha256User&password=password"),
        "RSA public key is not available client side");
  }

  @Test
  public void cachingSha256PluginTestSsl() throws Exception {
    Assumptions.assumeTrue(!isMariaDBServer() && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache

    Assumptions.assumeTrue(haveSsl());
    try (Connection con = createCon("user=cachingSha256User&password=password&sslMode=trust")) {
      con.isValid(1);
    }
  }
}
