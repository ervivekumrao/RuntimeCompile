package umrao.compile.engine;

import umrao.compile.store.TieredBytecodeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Isolated ClassLoader that loads dynamically compiled classes directly from memory buffers.
 * Supports child-first lookup for in-memory bytecodes, compilation paths (directories and JARs),
 * and tiered resolution (ClassLoader &gt; Redis &gt; Filesystem &gt; External DB).
 */
@SuppressWarnings("unused")
public class MemoryClassLoader extends ClassLoader {

    private final Map<String, byte[]> bytecodes = new ConcurrentHashMap<>();
    private final List<Path> compilationPaths = new CopyOnWriteArrayList<>();
    private final TieredBytecodeRegistry tieredRegistry;

    public MemoryClassLoader(ClassLoader parent) {
        this(parent, Collections.emptyMap(), Collections.emptyList(), null);
    }

    public MemoryClassLoader(ClassLoader parent, Map<String, byte[]> initialBytecodes) {
        this(parent, initialBytecodes, Collections.emptyList(), null);
    }

    public MemoryClassLoader(ClassLoader parent,
                             Map<String, byte[]> initialBytecodes,
                             List<Path> compilationPaths) {
        this(parent, initialBytecodes, compilationPaths, null);
    }

    public MemoryClassLoader(ClassLoader parent,
                             Map<String, byte[]> initialBytecodes,
                             List<Path> compilationPaths,
                             TieredBytecodeRegistry tieredRegistry) {
        super(parent != null ? parent : ClassLoader.getSystemClassLoader());
        if (initialBytecodes != null) {
            this.bytecodes.putAll(initialBytecodes);
        }
        if (compilationPaths != null) {
            this.compilationPaths.addAll(compilationPaths);
        }
        this.tieredRegistry = tieredRegistry;
    }

    public void addBytecode(String className, byte[] bytecode) {
        this.bytecodes.put(className, bytecode);
    }

    public void addAllBytecodes(Map<String, byte[]> newBytecodes) {
        if (newBytecodes != null) {
            this.bytecodes.putAll(newBytecodes);
        }
    }

    public void addCompilationPath(Path path) {
        if (path != null) {
            this.compilationPaths.add(path);
        }
    }

    public void addAllCompilationPaths(List<Path> paths) {
        if (paths != null) {
            this.compilationPaths.addAll(paths);
        }
    }

    public byte[] getBytecode(String className) {
        byte[] bytes = this.bytecodes.get(className);
        if (bytes == null && !compilationPaths.isEmpty()) {
            bytes = findClassInCompilationPaths(className);
            if (bytes != null) {
                this.bytecodes.put(className, bytes);
            }
        }
        if (bytes == null && tieredRegistry != null) {
            Optional<byte[]> tiered = tieredRegistry.findBytecode(className);
            if (tiered.isPresent()) {
                bytes = tiered.get();
                this.bytecodes.put(className, bytes);
            }
        }
        return bytes;
    }

    public boolean hasBytecode(String className) {
        return this.bytecodes.containsKey(className);
    }

    public boolean isClassLoaded(String className) {
        return findLoadedClass(className) != null;
    }

    public Map<String, byte[]> getAllBytecodes() {
        return Collections.unmodifiableMap(this.bytecodes);
    }

    public Set<String> getRegisteredClassNames() {
        return Collections.unmodifiableSet(this.bytecodes.keySet());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass == null) {
                // If this loader holds the bytecode for this class, define it here directly
                if (bytecodes.containsKey(name)) {
                    loadedClass = findClass(name);
                } else {
                    loadedClass = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = bytecodes.get(name);
        if (bytes == null && !compilationPaths.isEmpty()) {
            bytes = findClassInCompilationPaths(name);
            if (bytes != null) {
                bytecodes.put(name, bytes);
            }
        }
        if (bytes == null && tieredRegistry != null) {
            Optional<byte[]> tiered = tieredRegistry.findBytecode(name);
            if (tiered.isPresent()) {
                bytes = tiered.get();
                bytecodes.put(name, bytes);
            }
        }
        if (bytes == null) {
            return super.findClass(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    private byte[] findClassInCompilationPaths(String className) {
        String relativePath = className.replace('.', '/') + ".class";
        for (Path base : compilationPaths) {
            if (Files.isDirectory(base)) {
                Path classFile = base.resolve(relativePath);
                if (Files.isRegularFile(classFile)) {
                    try {
                        return Files.readAllBytes(classFile);
                    } catch (IOException ignored) {
                    }
                }
            } else if (Files.isRegularFile(base) && base.toString().endsWith(".jar")) {
                try (JarFile jarFile = new JarFile(base.toFile())) {
                    JarEntry entry = jarFile.getJarEntry(relativePath);
                    if (entry != null) {
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            return is.readAllBytes();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }
}
