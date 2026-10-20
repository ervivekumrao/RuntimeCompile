package umrao.compile.store;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists and resolves compiled class bytecodes in an external SQL database
 * (e.g. PostgreSQL, MySQL, MariaDB, Oracle, H2) running in a separate container/pod.
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlDialectInspection"})
public class DatabaseBytecodeStore implements BytecodeStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBytecodeStore.class);
    private static final String TABLE_NAME = "compiled_classes";

    private final DataSource dataSource;

    public DatabaseBytecodeStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        initSchema();
    }

    public DatabaseBytecodeStore(String jdbcUrl, String user, String password) {
        this.dataSource = createDataSource(jdbcUrl, user, password);
        initSchema();
    }

    private static DataSource createDataSource(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("jdbcUrl must not be null");
        }
        if (jdbcUrl.startsWith("jdbc:h2:")) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(jdbcUrl);
            if (user != null) ds.setUser(user);
            if (password != null) ds.setPassword(password);
            return ds;
        } else if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(jdbcUrl);
            if (user != null) ds.setUser(user);
            if (password != null) ds.setPassword(password);
            return ds;
        } else {
            // Generic fallback using standard DriverManager
            return new GenericDriverManagerDataSource(jdbcUrl, user, password);
        }
    }

    /**
     * Creates an in-memory SQL database store using H2 for local testing and lightweight usage.
     */
    public static DatabaseBytecodeStore h2InMemory(String dbName) {
        String url = "jdbc:h2:mem:" + (dbName != null ? dbName : "bytecode_db") + ";DB_CLOSE_DELAY=-1";
        return new DatabaseBytecodeStore(url, "sa", "");
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName().toLowerCase();
            String binaryType = dbProduct.contains("postgres") ? "BYTEA" : "BLOB";
            String sql = "CREATE TABLE IF NOT EXISTS compiled_classes (" +
                    "class_name VARCHAR(255) PRIMARY KEY, " +
                    "package_name VARCHAR(255) NOT NULL, " +
                    "bytecode " + binaryType + " NOT NULL, " +
                    "updated_at TIMESTAMP NOT NULL" +
                    ")";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                log.info("Database bytecode table '{}' initialized on dialect [{}]", TABLE_NAME, dbProduct);
            }
        } catch (SQLException e) {
            log.error("Failed to initialize database schema for bytecode store", e);
            throw new RuntimeException("Database initialization failure", e);
        }
    }

    @Override
    public String getStorageType() {
        return "DATABASE";
    }

    @Override
    public void save(String className, byte[] bytecode) {
        if (className == null || bytecode == null) {
            return;
        }

        String packageName = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
        Timestamp now = Timestamp.from(Instant.now());

        String updateSql = "UPDATE compiled_classes SET package_name = ?, bytecode = ?, updated_at = ? WHERE class_name = ?";
        String insertSql = "INSERT INTO compiled_classes (class_name, package_name, bytecode, updated_at) VALUES (?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int updatedRows;
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, packageName);
                    updateStmt.setBytes(2, bytecode);
                    updateStmt.setTimestamp(3, now);
                    updateStmt.setString(4, className);
                    updatedRows = updateStmt.executeUpdate();
                }

                if (updatedRows == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, className);
                        insertStmt.setString(2, packageName);
                        insertStmt.setBytes(3, bytecode);
                        insertStmt.setTimestamp(4, now);
                        insertStmt.executeUpdate();
                    }
                }
                conn.commit();
                log.debug("Saved bytecode for class {} into external database", className);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed saving bytecode to database for class {}", className, e);
            throw new RuntimeException("Database error saving " + className, e);
        }
    }

    @Override
    public Optional<byte[]> load(String className) {
        if (className == null) {
            return Optional.empty();
        }

        String sql = "SELECT bytecode FROM compiled_classes WHERE class_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, className);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] bytes = rs.getBytes("bytecode");
                    if (bytes != null) {
                        return Optional.of(bytes);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed retrieving bytecode from database for {}: {}", className, e.getMessage());
        }

        return Optional.empty();
    }

    @Override
    public boolean contains(String className) {
        if (className == null) {
            return false;
        }

        String sql = "SELECT 1 FROM compiled_classes WHERE class_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, className);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public Map<String, byte[]> loadPackage(String packageName, boolean recurse) {
        String sql;
        String param;
        if (recurse) {
            sql = "SELECT class_name, bytecode FROM compiled_classes WHERE package_name = ? OR package_name LIKE ?";
            param = packageName.isEmpty() ? "%" : packageName + ".%";
        } else {
            sql = "SELECT class_name, bytecode FROM compiled_classes WHERE package_name = ?";
            param = packageName;
        }

        Map<String, byte[]> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, packageName);
            if (recurse) {
                stmt.setString(2, param);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String className = rs.getString("class_name");
                    byte[] bytes = rs.getBytes("bytecode");
                    if (bytes != null) {
                        result.put(className, bytes);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed retrieving package {} from database: {}", packageName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM compiled_classes";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            log.warn("Failed clearing database bytecode table: {}", e.getMessage());
        }
    }

    private static class GenericDriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        public GenericDriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
