package umrao.compile.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists and resolves compiled class bytecodes directly on the local filesystem of the compiler pod.
 */
public class FileSystemBytecodeStore implements BytecodeStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBytecodeStore.class);
    private final Path rootDir;

    public FileSystemBytecodeStore(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir must not be null");
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize filesystem bytecode store at: " + rootDir, e);
        }
    }

    public FileSystemBytecodeStore(File rootDir) {
        this(rootDir != null ? rootDir.toPath() : null);
    }

    @Override
    public String getStorageType() {
        return "FILESYSTEM";
    }

    @SuppressWarnings("unused")
    public Path getRootDir() {
        return rootDir;
    }

    @Override
    public void save(String className, byte[] bytecode) {
        if (className == null || bytecode == null) {
            return;
        }
        try {
            Path targetFile = resolveClassFilePath(className);
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, bytecode);
            log.debug("Saved bytecode for {} to filesystem: {}", className, targetFile);
        } catch (IOException e) {
            log.error("Failed saving bytecode to filesystem for class {}", className, e);
            throw new RuntimeException("Failed saving bytecode for " + className, e);
        }
    }

    @Override
    public Optional<byte[]> load(String className) {
        if (className == null) {
            return Optional.empty();
        }
        Path targetFile = resolveClassFilePath(className);
        if (Files.isRegularFile(targetFile)) {
            try {
                return Optional.of(Files.readAllBytes(targetFile));
            } catch (IOException e) {
                log.warn("Failed reading bytecode from filesystem for class {}", className, e);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean contains(String className) {
        if (className == null) {
            return false;
        }
        return Files.isRegularFile(resolveClassFilePath(className));
    }

    @Override
    public Map<String, byte[]> loadPackage(String packageName, boolean recurse) {
        Path packageDir = resolvePackageDirPath(packageName);
        if (!Files.isDirectory(packageDir)) {
            return Collections.emptyMap();
        }

        Map<String, byte[]> result = new HashMap<>();
        int maxDepth = recurse ? Integer.MAX_VALUE : 1;

        try (Stream<Path> stream = Files.walk(packageDir, maxDepth)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(path -> {
                        try {
                            Path relative = rootDir.relativize(path);
                            String classPathStr = relative.toString().replace(File.separatorChar, '.');
                            String className = classPathStr.substring(0, classPathStr.length() - 6);
                            byte[] bytes = Files.readAllBytes(path);
                            result.put(className, bytes);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed walking package directory for {}: {}", packageName, e.getMessage());
        }

        return result;
    }

    @Override
    public void clear() {
        try (Stream<Path> stream = Files.walk(rootDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(rootDir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private Path resolveClassFilePath(String className) {
        String relative = className.replace('.', File.separatorChar) + ".class";
        return rootDir.resolve(relative);
    }

    private Path resolvePackageDirPath(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return rootDir;
        }
        return rootDir.resolve(packageName.replace('.', File.separatorChar));
    }
}
