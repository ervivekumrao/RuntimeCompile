package umrao.compile.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.api.CompilationDiagnostic;
import umrao.compile.api.CompilationException;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.cache.CompilationCache;
import umrao.compile.cache.LruCompilationCache;
import umrao.compile.security.CodeSecurityValidator;
import umrao.compile.security.SecurityPolicy;
import umrao.compile.store.BytecodeStore;
import umrao.compile.store.DatabaseBytecodeStore;
import umrao.compile.store.FileSystemBytecodeStore;
import umrao.compile.store.RedisBytecodeStore;
import umrao.compile.store.TieredBytecodeRegistry;

import javax.sql.DataSource;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Enterprise production-grade implementation of DynamicCompiler backed by standard javax.tools.JavaCompiler.
 * Compiles Java classes completely in RAM with zero disk I/O, security validation, thread-safe caching,
 * and automatic dependency resolution across tiered persistence:
 * <b>ClassLoader &gt; Redis &gt; Filesystem &gt; External DB</b>.
 */
@SuppressWarnings("unused")
public class InMemoryCompiler implements DynamicCompiler {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCompiler.class);

    private final JavaCompiler compiler;
    private final CodeSecurityValidator securityValidator;
    private final CompilationCache cache;

    private volatile ClassLoader parentClassLoader;
    private final List<Path> compilationPaths = new CopyOnWriteArrayList<>();
    private volatile TieredBytecodeRegistry tieredRegistry;
    private volatile MemoryClassLoader sharedClassLoader;

    public InMemoryCompiler() {
        this(SecurityPolicy.permissive(), null, null, Collections.emptyList(), null);
    }

    public InMemoryCompiler(ClassLoader parentClassLoader) {
        this(SecurityPolicy.permissive(), null, parentClassLoader, Collections.emptyList(), null);
    }

    public InMemoryCompiler(SecurityPolicy securityPolicy, CompilationCache cache, ClassLoader parentClassLoader) {
        this(securityPolicy, cache, parentClassLoader, Collections.emptyList(), null);
    }

    public InMemoryCompiler(SecurityPolicy securityPolicy,
                            CompilationCache cache,
                            ClassLoader parentClassLoader,
                            List<Path> compilationPaths) {
        this(securityPolicy, cache, parentClassLoader, compilationPaths, null);
    }

    public InMemoryCompiler(SecurityPolicy securityPolicy,
                            CompilationCache cache,
                            ClassLoader parentClassLoader,
                            List<Path> compilationPaths,
                            TieredBytecodeRegistry tieredRegistry) {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            throw new IllegalStateException("System Java Compiler not found. Ensure RuntimeCompile is executed on a JDK, not a JRE.");
        }
        SecurityPolicy effectivePolicy = securityPolicy != null ? securityPolicy : SecurityPolicy.permissive();
        this.securityValidator = new CodeSecurityValidator(effectivePolicy);
        this.cache = cache != null ? cache : new LruCompilationCache();
        this.parentClassLoader = parentClassLoader != null
                ? parentClassLoader
                : Thread.currentThread().getContextClassLoader();
        if (this.parentClassLoader == null) {
            this.parentClassLoader = ClassLoader.getSystemClassLoader();
        }
        if (compilationPaths != null) {
            this.compilationPaths.addAll(compilationPaths);
        }
        this.tieredRegistry = tieredRegistry != null
                ? tieredRegistry
                : new TieredBytecodeRegistry(this.parentClassLoader, null, null, null);
        this.sharedClassLoader = new MemoryClassLoader(this.parentClassLoader, null, this.compilationPaths, this.tieredRegistry);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompilationResult<T> compile(String fullClassName, String sourceCode, Class<T> targetType) {
        if (fullClassName == null || fullClassName.isBlank()) {
            throw new IllegalArgumentException("Target class name must not be blank.");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code must not be blank.");
        }
        Class<T> expectedType = targetType != null ? targetType : (Class<T>) Object.class;

        // 1. Enforce AST / lexical security policy rules
        securityValidator.validate(sourceCode);

        // 2. Check thread-safe LRU compilation cache
        String cacheKey = LruCompilationCache.computeKey(fullClassName, sourceCode);
        Optional<CompilationResult<T>> cached = cache.get(cacheKey, expectedType);
        if (cached.isPresent()) {
            log.debug("Cache hit for class {} (key={})", fullClassName, cacheKey);
            return cached.get();
        }

        long startTime = System.currentTimeMillis();

        // 3. Set up in-memory source and file manager with tiered resolution (ClassLoader > Redis > Filesystem > DB)
        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnosticCollector, null, null);

        try (InMemoryFileManager inMemoryFileManager = new InMemoryFileManager(standardFileManager, tieredRegistry)) {

            SourceJavaFileObject sourceObject = new SourceJavaFileObject(fullClassName, sourceCode);

            // 4. Build compiler options with dynamically resolved compilation paths
            List<String> options = buildCompilerOptions(expectedType);

            // 5. Execute compilation task
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    inMemoryFileManager,
                    diagnosticCollector,
                    options,
                    null,
                    List.of(sourceObject)
            );

            boolean success = Boolean.TRUE.equals(task.call());
            long duration = System.currentTimeMillis() - startTime;

            List<CompilationDiagnostic> diagnostics = diagnosticCollector.getDiagnostics().stream()
                    .map(CompilationDiagnostic::from)
                    .collect(Collectors.toList());

            if (!success) {
                log.warn("In-memory compilation failed for {} with {} diagnostics", fullClassName, diagnostics.size());
                throw new CompilationException("Compilation failed for class " + fullClassName, diagnostics);
            }

            // 6. Collect generated bytecodes and persist across tiered storage (RAM, Redis, Filesystem, DB)
            Map<String, BytecodeJavaFileObject> generated = inMemoryFileManager.getBytecodes();
            Map<String, byte[]> newlyGenerated = new HashMap<>();
            for (Map.Entry<String, BytecodeJavaFileObject> entry : generated.entrySet()) {
                byte[] bytes = entry.getValue().getByteCode();
                tieredRegistry.save(entry.getKey(), bytes);
                newlyGenerated.put(entry.getKey(), bytes);
            }

            // 7. Load into shared or isolated MemoryClassLoader
            MemoryClassLoader classLoader;
            synchronized (this) {
                if (sharedClassLoader.isClassLoaded(fullClassName)) {
                    // Class redefinition: use isolated child loader to prevent duplicate class definition linkage error
                    classLoader = new MemoryClassLoader(sharedClassLoader, newlyGenerated, this.compilationPaths, this.tieredRegistry);
                } else {
                    sharedClassLoader.addAllBytecodes(newlyGenerated);
                    classLoader = sharedClassLoader;
                }
            }

            try {
                Class<?> rawClass = classLoader.loadClass(fullClassName);

                if (!expectedType.isAssignableFrom(rawClass)) {
                    throw new CompilationException(
                            String.format("Compiled class %s does not implement or extend expected type %s",
                                    fullClassName, expectedType.getName()),
                            diagnostics
                    );
                }

                Class<? extends T> typedClass = (Class<? extends T>) rawClass;
                CompilationResult<T> successResult = CompilationResult.success(typedClass, diagnostics, duration, false);

                // Cache successful result
                cache.put(cacheKey, successResult);
                log.debug("Compiled {} successfully in {}ms", fullClassName, duration);

                return successResult;
            } catch (ClassNotFoundException e) {
                log.error("Failed to load compiled class from memory: {}", fullClassName, e);
                throw new CompilationException("Failed to load compiled class from memory: " + fullClassName, e);
            }
        } catch (IOException e) {
            log.error("I/O error during in-memory compilation", e);
            throw new CompilationException("I/O error during in-memory compilation", e);
        }
    }

    @Override
    public Map<String, CompilationResult<Object>> compileBatch(Map<String, String> classesByName) {
        if (classesByName == null || classesByName.isEmpty()) {
            return Collections.emptyMap();
        }

        // Validate security on all input sources first
        for (String code : classesByName.values()) {
            securityValidator.validate(code);
        }

        long startTime = System.currentTimeMillis();
        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnosticCollector, null, null);

        try (InMemoryFileManager inMemoryFileManager = new InMemoryFileManager(standardFileManager, tieredRegistry)) {

            List<JavaFileObject> sourceObjects = new ArrayList<>();
            for (Map.Entry<String, String> entry : classesByName.entrySet()) {
                sourceObjects.add(new SourceJavaFileObject(entry.getKey(), entry.getValue()));
            }

            List<String> options = buildCompilerOptions(Object.class);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    inMemoryFileManager,
                    diagnosticCollector,
                    options,
                    null,
                    sourceObjects
            );

            boolean success = Boolean.TRUE.equals(task.call());
            long duration = System.currentTimeMillis() - startTime;

            List<CompilationDiagnostic> diagnostics = diagnosticCollector.getDiagnostics().stream()
                    .map(CompilationDiagnostic::from)
                    .collect(Collectors.toList());

            if (!success) {
                throw new CompilationException("Batch compilation failed", diagnostics);
            }

            Map<String, byte[]> newlyGenerated = new HashMap<>();
            for (Map.Entry<String, BytecodeJavaFileObject> entry : inMemoryFileManager.getBytecodes().entrySet()) {
                byte[] bytes = entry.getValue().getByteCode();
                tieredRegistry.save(entry.getKey(), bytes);
                newlyGenerated.put(entry.getKey(), bytes);
            }

            MemoryClassLoader classLoader;
            synchronized (this) {
                sharedClassLoader.addAllBytecodes(newlyGenerated);
                classLoader = sharedClassLoader;
            }

            Map<String, CompilationResult<Object>> results = new LinkedHashMap<>();
            for (String className : classesByName.keySet()) {
                try {
                    Class<?> loaded = classLoader.loadClass(className);
                    CompilationResult<Object> res = CompilationResult.success(loaded, diagnostics, duration, false);
                    results.put(className, res);
                } catch (ClassNotFoundException e) {
                    throw new CompilationException("Failed loading batch-compiled class: " + className, e);
                }
            }

            return results;
        } catch (IOException e) {
            throw new CompilationException("I/O error during batch compilation", e);
        }
    }

    @Override
    public void addClasspath(Path path) {
        if (path != null) {
            this.compilationPaths.add(path);
            synchronized (this) {
                this.sharedClassLoader.addCompilationPath(path);
            }
        }
    }

    @Override
    public List<Path> getCompilationPaths() {
        return Collections.unmodifiableList(compilationPaths);
    }

    @Override
    public ClassLoader getClassLoader() {
        return parentClassLoader;
    }

    @Override
    public void setClassLoader(ClassLoader classLoader) {
        this.parentClassLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        this.tieredRegistry.setClassLoader(this.parentClassLoader);
        synchronized (this) {
            this.sharedClassLoader = new MemoryClassLoader(
                    this.parentClassLoader,
                    this.tieredRegistry.getInMemoryRepository(),
                    this.compilationPaths,
                    this.tieredRegistry
            );
        }
    }

    @Override
    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return sharedClassLoader.loadClass(className);
    }

    @Override
    public TieredBytecodeRegistry getTieredRegistry() {
        return tieredRegistry;
    }

    @Override
    public void setTieredRegistry(TieredBytecodeRegistry tieredRegistry) {
        this.tieredRegistry = tieredRegistry != null
                ? tieredRegistry
                : new TieredBytecodeRegistry(this.parentClassLoader, null, null, null);
        synchronized (this) {
            this.sharedClassLoader = new MemoryClassLoader(
                    this.parentClassLoader,
                    this.tieredRegistry.getInMemoryRepository(),
                    this.compilationPaths,
                    this.tieredRegistry
            );
        }
    }

    @Override
    public void registerCompiledClass(String className, byte[] bytecode) {
        if (className != null && bytecode != null) {
            tieredRegistry.save(className, bytecode);
            synchronized (this) {
                sharedClassLoader.addBytecode(className, bytecode);
            }
        }
    }

    @Override
    public byte[] getCompiledBytecode(String className) {
        return tieredRegistry.findBytecode(className).orElse(null);
    }

    @Override
    public Map<String, byte[]> getCompiledBytecodes() {
        return Collections.unmodifiableMap(tieredRegistry.getInMemoryRepository());
    }

    @Override
    public void clearCache() {
        cache.invalidateAll();
        tieredRegistry.clearAllTiers();
        synchronized (this) {
            this.sharedClassLoader = new MemoryClassLoader(this.parentClassLoader, null, this.compilationPaths, this.tieredRegistry);
        }
    }

    public void clearCompiledClasses() {
        tieredRegistry.clearAllTiers();
        synchronized (this) {
            this.sharedClassLoader = new MemoryClassLoader(this.parentClassLoader, null, this.compilationPaths, this.tieredRegistry);
        }
    }

    private List<String> buildCompilerOptions(Class<?> targetType) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");

        Set<String> cpElements = new LinkedHashSet<>();

        // 1. Add explicitly configured compilation paths (JARs and directories)
        for (Path path : compilationPaths) {
            cpElements.add(path.toAbsolutePath().toString());
        }

        // 2. Add target classloader URLs if URLClassLoader
        if (targetType != null) {
            addClassLoaderUrls(targetType.getClassLoader(), cpElements);
        }

        // 3. Add parentClassLoader URLs if URLClassLoader
        if (parentClassLoader != null) {
            addClassLoaderUrls(parentClassLoader, cpElements);
        }

        // 4. Add standard java.class.path
        String javaClassPath = System.getProperty("java.class.path");
        if (javaClassPath != null && !javaClassPath.isBlank()) {
            Collections.addAll(cpElements, javaClassPath.split(File.pathSeparator));
        }

        if (!cpElements.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, cpElements));
        }

        return options;
    }

    private void addClassLoaderUrls(ClassLoader cl, Set<String> cpElements) {
        while (cl != null) {
            if (cl instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    try {
                        cpElements.add(new File(url.toURI()).getAbsolutePath());
                    } catch (Exception e) {
                        cpElements.add(new File(url.getFile()).getAbsolutePath());
                    }
                }
            }
            cl = cl.getParent();
        }
    }

    public static class Builder {
        private SecurityPolicy securityPolicy = SecurityPolicy.permissive();
        private CompilationCache cache = new LruCompilationCache();
        private ClassLoader classLoader;
        private final List<Path> compilationPaths = new ArrayList<>();
        private BytecodeStore redisStore;
        private BytecodeStore fileSystemStore;
        private BytecodeStore databaseStore;
        private TieredBytecodeRegistry tieredRegistry;

        public Builder withSecurityPolicy(SecurityPolicy policy) {
            this.securityPolicy = policy;
            return this;
        }

        public Builder withCache(CompilationCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder withClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder addClasspath(Path path) {
            if (path != null) {
                this.compilationPaths.add(path);
            }
            return this;
        }

        public Builder addClasspath(String path) {
            if (path != null && !path.isBlank()) {
                this.compilationPaths.add(Path.of(path));
            }
            return this;
        }

        public Builder addClasspath(File file) {
            if (file != null) {
                this.compilationPaths.add(file.toPath());
            }
            return this;
        }

        public Builder withRedisStore(BytecodeStore redisStore) {
            this.redisStore = redisStore;
            return this;
        }

        public Builder withRedis(String host, int port) {
            this.redisStore = new RedisBytecodeStore(host, port);
            return this;
        }

        public Builder withFileSystemStore(BytecodeStore fileSystemStore) {
            this.fileSystemStore = fileSystemStore;
            return this;
        }

        public Builder withFileSystemStore(Path rootDir) {
            this.fileSystemStore = new FileSystemBytecodeStore(rootDir);
            return this;
        }

        public Builder withDatabaseStore(BytecodeStore databaseStore) {
            this.databaseStore = databaseStore;
            return this;
        }

        public Builder withDatabase(DataSource dataSource) {
            this.databaseStore = new DatabaseBytecodeStore(dataSource);
            return this;
        }

        public Builder withDatabase(String jdbcUrl, String user, String password) {
            this.databaseStore = new DatabaseBytecodeStore(jdbcUrl, user, password);
            return this;
        }

        public Builder withTieredRegistry(TieredBytecodeRegistry tieredRegistry) {
            this.tieredRegistry = tieredRegistry;
            return this;
        }

        public InMemoryCompiler build() {
            TieredBytecodeRegistry registry = this.tieredRegistry != null
                    ? this.tieredRegistry
                    : new TieredBytecodeRegistry(this.classLoader, this.redisStore, this.fileSystemStore, this.databaseStore);

            return new InMemoryCompiler(securityPolicy, cache, classLoader, compilationPaths, registry);
        }
    }
}
