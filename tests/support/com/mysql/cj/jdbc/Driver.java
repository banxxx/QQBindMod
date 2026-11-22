package com.mysql.cj.jdbc;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
/** Test-only explicit guard: construction counted, any connection is forbidden. */
public final class Driver implements java.sql.Driver {
    public static int created;
    public static java.sql.Driver delegate;
    public Driver() throws SQLException { created++; }
    public Connection connect(String url,Properties info) throws SQLException {
        if (delegate == null) throw new AssertionError("Bridge test must never connect");
        return delegate.connect(url,info);
    }
    public boolean acceptsURL(String url) { return false; }
    public DriverPropertyInfo[] getPropertyInfo(String url,Properties info) { return new DriverPropertyInfo[0]; }
    public int getMajorVersion() { return 0; }
    public int getMinorVersion() { return 0; }
    public boolean jdbcCompliant() { return false; }
    public Logger getParentLogger() { throw new AssertionError("No logging discovery"); }
}
