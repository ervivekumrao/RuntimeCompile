package umrao.compile.service.config;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 12-Factor App configuration model for CompilerHttpService.
 * Reads configurations dynamically from environment variables, system properties, or programmatic builder.
 */
public record CompilerServiceConfig(
        int port,
        Path storageDir,
        String redisHost,
        int redisPort,
        String redisPassword,
        String dbUrl,
        String dbUser,
        String dbPassword
) {

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_REDIS_PORT = 6379;

    public static Path resolveDefaultStorageDir() {
        File varData = new File("/var/data");
        if (varData.exists() && varData.canWrite()) {
            return Paths.get("/var/data/compiler/classes");
        }
        return Paths.get("build/compiler-storage");
    }

    public static CompilerServiceConfig fromEnvironment() {
        int port = getEnvInt("SERVER_PORT", DEFAULT_PORT);
        String storageDirStr = getEnvString("STORAGE_DIR", resolveDefaultStorageDir().toString());
        String redisHost = getEnvString("REDIS_HOST", null);
        int redisPort = getEnvInt("REDIS_PORT", DEFAULT_REDIS_PORT);
        String redisPassword = getEnvString("REDIS_PASSWORD", null);
        String dbUrl = getEnvString("DB_URL", null);
        String dbUser = getEnvString("DB_USER", null);
        String dbPassword = getEnvString("DB_PASSWORD", null);

        return new CompilerServiceConfig(
                port,
                Paths.get(storageDirStr),
                redisHost,
                redisPort,
                redisPassword,
                dbUrl,
                dbUser,
                dbPassword
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String getEnvString(String key, String defaultValue) {
        String sysProp = System.getProperty(key.toLowerCase().replace('_', '.'));
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String env = System.getenv(key);
        return (env != null && !env.isBlank()) ? env : defaultValue;
    }

    private static int getEnvInt(String key, int defaultValue) {
        String val = getEnvString(key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unused")
    public static class Builder {
        private int port = DEFAULT_PORT;
        private Path storageDir = resolveDefaultStorageDir();
        private String redisHost;
        private int redisPort = DEFAULT_REDIS_PORT;
        private String redisPassword;
        private String dbUrl;
        private String dbUser;
        private String dbPassword;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder storageDir(Path storageDir) {
            this.storageDir = storageDir;
            return this;
        }

        public Builder redis(String host, int port) {
            this.redisHost = host;
            this.redisPort = port;
            return this;
        }

        public Builder redisPassword(String password) {
            this.redisPassword = password;
            return this;
        }

        public Builder database(String url, String user, String password) {
            this.dbUrl = url;
            this.dbUser = user;
            this.dbPassword = password;
            return this;
        }

        public CompilerServiceConfig build() {
            return new CompilerServiceConfig(
                    port, storageDir, redisHost, redisPort, redisPassword, dbUrl, dbUser, dbPassword
            );
        }
    }
}
