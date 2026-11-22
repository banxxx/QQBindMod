package com.poso.qqbind.official;

import java.sql.*;
import java.util.*;
import java.util.function.*;

/** Single-host, one-attempt Connector/J factory; explicit caller-owned settings.
 * No DriverManager discovery, old pool, URL options, config file or env lookup.
 * TLS verifies identity using the JVM's configured public trust roots. Custom
 * trust-store deployment, credentials and driver creation remain owner tasks.
 */
public final class OfficialJdbcConnections implements OfficialJdbcCenter.Connections {
    public enum Access { READ_ONLY, READ_WRITE }
    private final Driver driver;
    private final Supplier<Properties> settings;
    private final BooleanSupplier active;
    private final Access access;
    public OfficialJdbcConnections(Driver driver, Supplier<Properties> settings, BooleanSupplier active) {
        this(driver,settings,active,Access.READ_ONLY);
    }
    public OfficialJdbcConnections(Driver driver, Supplier<Properties> settings, BooleanSupplier active, Access access) {
        this.driver = Objects.requireNonNull(driver); this.settings = Objects.requireNonNull(settings); this.active = Objects.requireNonNull(active);
        this.access = Objects.requireNonNull(access);
    }
    private void requireActive() { if (!active.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED"); }
    @Override public Connection open() {
        Connection connection = null;
        try {
            requireActive(); Properties config = settings.get();
            if (config == null || !config.stringPropertyNames().equals(Set.of("host","port","database","user","password"))) throw new IllegalArgumentException();
            String host = config.getProperty("host"), database = config.getProperty("database"), port = config.getProperty("port");
            if (!host.matches("[A-Za-z0-9.-]{1,253}") || !database.matches("[A-Za-z0-9_]{1,64}") || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
            int number = Integer.parseInt(port); if (number < 1 || number > 65535) throw new IllegalArgumentException();
            for (String field : List.of("user","password")) if (config.getProperty(field).isEmpty() || config.getProperty(field).indexOf(0) >= 0) throw new IllegalArgumentException();
            Properties options = new Properties(); options.setProperty("user",config.getProperty("user")); options.setProperty("password",config.getProperty("password"));
            options.setProperty("autoReconnect","false"); options.setProperty("autoReconnectForPools","false"); options.setProperty("reconnectAtTxEnd","false");
            options.setProperty("sslMode","VERIFY_IDENTITY"); options.setProperty("allowPublicKeyRetrieval","false");
            options.setProperty("allowLoadLocalInfile","false"); options.setProperty("allowUrlInLocalInfile","false");
            options.setProperty("connectTimeout","5000"); options.setProperty("socketTimeout","5000"); options.setProperty("characterEncoding","UTF-8");
            requireActive(); connection = driver.connect("jdbc:mysql://" + host + ":" + number + "/" + database,options);
            if (connection == null) throw new SQLException();
            requireActive();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SET SESSION time_zone = '+00:00'");
                statement.execute("SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'");
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(access == Access.READ_ONLY); connection.setAutoCommit(false); requireActive(); return connection;
        } catch (OfficialException e) { close(connection); throw e; }
        catch (SQLException e) {
            close(connection);
            throw new OfficialException(Set.of(1044,1045,1142,1143).contains(e.getErrorCode()) ? "UNAUTHORIZED" : "DB_UNAVAILABLE");
        }
        catch (Exception e) { close(connection); throw new OfficialException("DB_UNAVAILABLE"); }
    }
    static void close(Connection connection) { if (connection != null) try { connection.close(); } catch (Exception ignored) { } }
}
