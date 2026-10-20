package umrao.compile.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.engine.MemoryClassLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Orchestrates multi-tier dynamic class persistence and resolution following the priority chain:
 * <b>ClassLoader &gt; Redis (In-Memory Cache) &gt; Filesystem (Pod Local) &gt; DB (External SQL Database)</b>.
 * <p>
 * Automatically warms upper tiers when a dependent class is retrieved from a lower tier.
 */
public class TieredBytecodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(TieredBytecodeRegistry.class);

    private final Map<String, byte[]> inMemoryRepository = new ConcurrentHashMap<>();
    private volatile ClassLoader classLoader;
    private final BytecodeStore redisStore;
    private final BytecodeStore fileSystemStore;
    private final BytecodeStore databaseStore;

    public TieredBytecodeRegistry(ClassLoader classLoader,
                                  BytecodeStore redisStore,
                                  BytecodeStore fileSystemStore,
                                  BytecodeStore databaseStore) {
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        this.redisStore = redisStore;
        this.fileSystemStore = fileSystemStore;
        this.databaseStore = databaseStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Persists bytecode to all configured active storage tiers.
     */
    public void save(String className, byte[] bytecode) {
        if (className == null || bytecode == null) {
            return;
        }

        // 1. Always retain in RAM repository
        inMemoryRepository.put(className, bytecode);

        // 2. Persist to Redis (fast distributed in-memory cache)
        if (redisStore != null) {
            try {
                redisStore.save(className, bytecode);
            } catch (Exception e) {
                log.warn("Failed saving class {} to Redis tier: {}", className, e.getMessage());
            }
        }

        // 3. Persist to Filesystem (compiler pod local storage / volume)
        if (fileSystemStore != null) {
            try {
                fileSystemStore.save(className, bytecode);
            } catch (Exception e) {
                log.warn("Failed saving class {} to Filesystem tier: {}", className, e.getMessage());
            }
        }

        // 4. Persist to External Database (cluster-wide permanent storage)
        if (databaseStore != null) {
            try {
                databaseStore.save(className, bytecode);
            } catch (Exception e) {
                log.warn("Failed saving class {} to Database tier: {}", className, e.getMessage());
            }
        }
    }

    /**
     * Resolves bytecode for a class following the strict priority chain:
     * <b>ClassLoader &gt; Redis &gt; Filesystem &gt; DB</b>.
     * Automatically warms higher tiers on cache hit.
     */
    public Optional<byte[]> findBytecode(String className) {
        if (className == null || className.isBlank()) {
            return Optional.empty();
        }

        // Tier 1: Check in-memory RAM repository & ClassLoader
        byte[] ramBytes = inMemoryRepository.get(className);
        if (ramBytes != null) {
            return Optional.of(ramBytes);
        }

        if (classLoader instanceof MemoryClassLoader memLoader) {
            byte[] memBytes = memLoader.getBytecode(className);
            if (memBytes != null) {
                inMemoryRepository.put(className, memBytes);
                return Optional.of(memBytes);
            }
        }

        if (classLoader != null) {
            String resourcePath = className.replace('.', '/') + ".class";
            try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    byte[] clBytes = is.readAllBytes();
                    inMemoryRepository.put(className, clBytes);
                    return Optional.of(clBytes);
                }
            } catch (IOException ignored) {
            }
        }

        // Tier 2: Check Redis (In-Memory Cache)
        if (redisStore != null) {
            Optional<byte[]> redisResult = redisStore.load(className);
            if (redisResult.isPresent()) {
                byte[] bytes = redisResult.get();
                inMemoryRepository.put(className, bytes);
                log.debug("Resolved {} from Tier 2 [Redis]", className);
                return Optional.of(bytes);
            }
        }

        // Tier 3: Check Filesystem (Compiler Pod Local Storage)
        if (fileSystemStore != null) {
            Optional<byte[]> fsResult = fileSystemStore.load(className);
            if (fsResult.isPresent()) {
                byte[] bytes = fsResult.get();
                inMemoryRepository.put(className, bytes);
                // Warm Tier 2 (Redis)
                if (redisStore != null) {
                    try {
                        redisStore.save(className, bytes);
                    } catch (Exception ignored) {
                    }
                }
                log.debug("Resolved {} from Tier 3 [Filesystem], warmed Redis", className);
                return Optional.of(bytes);
            }
        }

        // Tier 4: Check External Database
        if (databaseStore != null) {
            Optional<byte[]> dbResult = databaseStore.load(className);
            if (dbResult.isPresent()) {
                byte[] bytes = dbResult.get();
                inMemoryRepository.put(className, bytes);
                // Warm Tier 2 (Redis) & Tier 3 (Filesystem)
                if (redisStore != null) {
                    try {
                        redisStore.save(className, bytes);
                    } catch (Exception ignored) {
                    }
                }
                if (fileSystemStore != null) {
                    try {
                        fileSystemStore.save(className, bytes);
                    } catch (Exception ignored) {
                    }
                }
                log.debug("Resolved {} from Tier 4 [Database], warmed Redis and Filesystem", className);
                return Optional.of(bytes);
            }
        }

        return Optional.empty();
    }

    /**
     * Resolves all classes in a package combining across all tiers
     * where higher priority tiers override lower priority tiers.
     */
    public Map<String, byte[]> findClassesInPackage(String packageName, boolean recurse) {
        Map<String, byte[]> combined = new HashMap<>();

        // 1. Lower priority: DB
        if (databaseStore != null) {
            combined.putAll(databaseStore.loadPackage(packageName, recurse));
        }

        // 2. Medium-low priority: Filesystem (overrides DB)
        if (fileSystemStore != null) {
            combined.putAll(fileSystemStore.loadPackage(packageName, recurse));
        }

        // 3. Medium-high priority: Redis (overrides Filesystem)
        if (redisStore != null) {
            combined.putAll(redisStore.loadPackage(packageName, recurse));
        }

        // 4. Highest priority: ClassLoader & RAM (overrides Redis)
        addClassLoaderPackageClasses(packageName, recurse, combined);

        for (Map.Entry<String, byte[]> entry : inMemoryRepository.entrySet()) {
            String cn = entry.getKey();
            if (matchesPackage(cn, packageName, recurse)) {
                combined.put(cn, entry.getValue());
            }
        }

        return combined;
    }

    private void addClassLoaderPackageClasses(String packageName, boolean recurse, Map<String, byte[]> target) {
        if (classLoader instanceof MemoryClassLoader mem) {
            for (Map.Entry<String, byte[]> entry : mem.getAllBytecodes().entrySet()) {
                String cn = entry.getKey();
                if (matchesPackage(cn, packageName, recurse)) {
                    target.put(cn, entry.getValue());
                }
            }
        }

        if (classLoader == null) {
            return;
        }

        String packagePath = packageName.replace('.', '/');
        try {
            Enumeration<URL> urls = classLoader.getResources(packagePath);
            while (urls != null && urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equalsIgnoreCase(protocol)) {
                    try {
                        File dir = new File(url.toURI());
                        if (dir.isDirectory()) {
                            scanDirectory(dir, packageName, recurse, target);
                        }
                    } catch (Exception ignored) {
                    }
                } else if ("jar".equalsIgnoreCase(protocol)) {
                    scanJarUrl(url, packageName, recurse, target);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void scanDirectory(File dir, String currentPkg, boolean recurse, Map<String, byte[]> target) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory() && recurse) {
                String subPkg = currentPkg.isEmpty() ? file.getName() : currentPkg + "." + file.getName();
                scanDirectory(file, subPkg, recurse, target);
            } else if (file.isFile() && file.getName().endsWith(".class")) {
                String simpleName = file.getName().substring(0, file.getName().length() - 6);
                String fullClassName = currentPkg.isEmpty() ? simpleName : currentPkg + "." + simpleName;
                if (!target.containsKey(fullClassName)) {
                    try {
                        target.put(fullClassName, Files.readAllBytes(file.toPath()));
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void scanJarUrl(URL url, String packageName, boolean recurse, Map<String, byte[]> target) {
        try {
            URLConnection conn = url.openConnection();
            if (conn instanceof JarURLConnection jarConn) {
                JarFile jarFile = jarConn.getJarFile();
                String prefix = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".class")) {
                        String rel = name.substring(prefix.length());
                        if (!recurse && rel.contains("/")) {
                            continue;
                        }
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        if (!target.containsKey(className)) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                target.put(className, is.readAllBytes());
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean matchesPackage(String fullClassName, String packageName, boolean recurse) {
        int lastDot = fullClassName.lastIndexOf('.');
        String pkg = lastDot > 0 ? fullClassName.substring(0, lastDot) : "";
        if (packageName.isEmpty()) {
            return recurse || pkg.isEmpty();
        }
        if (recurse) {
            return pkg.equals(packageName) || pkg.startsWith(packageName + ".");
        }
        return pkg.equals(packageName);
    }

    public Map<String, byte[]> getInMemoryRepository() {
        return inMemoryRepository;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    public BytecodeStore getRedisStore() {
        return redisStore;
    }

    public BytecodeStore getFileSystemStore() {
        return fileSystemStore;
    }

    public BytecodeStore getDatabaseStore() {
        return databaseStore;
    }

    public void clearAllTiers() {
        inMemoryRepository.clear();
        if (redisStore != null) redisStore.clear();
        if (fileSystemStore != null) fileSystemStore.clear();
        if (databaseStore != null) databaseStore.clear();
    }

    public static class Builder {
        private ClassLoader classLoader;
        private BytecodeStore redisStore;
        private BytecodeStore fileSystemStore;
        private BytecodeStore databaseStore;

        @SuppressWarnings("unused")
        public Builder withClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder withRedisStore(BytecodeStore redisStore) {
            this.redisStore = redisStore;
            return this;
        }

        public Builder withFileSystemStore(BytecodeStore fileSystemStore) {
            this.fileSystemStore = fileSystemStore;
            return this;
        }

        public Builder withDatabaseStore(BytecodeStore databaseStore) {
            this.databaseStore = databaseStore;
            return this;
        }

        public TieredBytecodeRegistry build() {
            return new TieredBytecodeRegistry(classLoader, redisStore, fileSystemStore, databaseStore);
        }
    }
}
