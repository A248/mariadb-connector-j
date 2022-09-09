// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.mariadb.jdbc.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLNonTransientConnectionException;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class UnixsocketTest extends Common {
  @Test
  public void testConnectWithUnixSocketWhenDBNotUp() throws IOException {
    Assumptions.assumeTrue(!isWindows());
    Assumptions.assumeTrue(
        !"maxscale".equals(System.getenv("srv"))
            && !"skysql".equals(System.getenv("srv"))
            && !"xpand".equals(System.getenv("srv"))
            && !"skysql-ha".equals(System.getenv("srv")));

    String url = mDefUrl + "&localSocket=/tmp/not_valid_socket&localSocketAddress=localhost";

    java.sql.Driver driver = new org.mariadb.jdbc.Driver();

    Runtime rt = Runtime.getRuntime();
    // System.out.println("netstat-apnx | grep " + ProcessHandle.current().pid());
    String[] commands = {"/bin/sh", "-c", "netstat -apnx | grep " + ProcessHandle.current().pid()};
    Process proc = rt.exec(commands);

    BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    int initialLines = 0;
    while (stdInput.readLine() != null) {
      initialLines++;
    }
    proc.destroy();

    for (int i = 0; i < 10; i++) {
      assertThrows(
          SQLNonTransientConnectionException.class,
          () -> {
            driver.connect(url, new Properties());
          });
    }
    proc = rt.exec(commands);
    stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    int finalLines = 0;
    while (stdInput.readLine() != null) {
      finalLines++;
    }
    proc.destroy();
    assertEquals(
        finalLines,
        initialLines,
        "Error Leaking socket file descriptors. initial :"
            + initialLines
            + " but ending with "
            + finalLines);
  }
}
