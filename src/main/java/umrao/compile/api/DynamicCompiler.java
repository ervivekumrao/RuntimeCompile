package umrao.compile.api;

import umrao.compile.engine.JavaSourceParser;
import umrao.compile.store.TieredBytecodeRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-performance, in-memory, thread-safe dynamic Java compiler interface.
 * Supports taking only source code input (auto-inferring class declarations)
 * and resolving dependencies across tiered persistence: ClassLoader &gt; Redis &gt; Filesystem &gt; DB.
 */
@SuppressWarnings("unused")
public interface DynamicCompiler {

    /**
     * Compiles Java source code into bytecode in-memory, loads it into an isolated classloader,
     * and validates that the resulting class implements or extends the specified target type.
     *
     * @param fullClassName Fully qualified class name (e.g. "umrao.generated.DynamicGreeter")
     * @param sourceCode    Java source code string
     * @param targetType    Interface or class that the compiled class is expected to implement/extend
     * @param <T>           Target type parameter
     * @return CompilationResult containing diagnostics and the loaded Class
     * @throws CompilationException if compilation fails
     */
    <T> CompilationResult<T> compile(String fullClassName, String sourceCode, Class<T> targetType);

    /**
     * Compiles Java source code into bytecode in-memory given class name and source code.
     */
    default CompilationResult<Object> compile(String fullClassName, String sourceCode) {
        return compile(fullClassName, sourceCode, Object.class);
    }

    /**
     * Compiles Java source code taking ONLY the input of the class to compile.
     * Automatically infers package and class name from the source code declaration.
     *
     * @param sourceCode Java source code string
     * @return CompilationResult containing loaded Class and dynamic invocation helpers
     */
    default CompilationResult<Object> compile(String sourceCode) {
        String fullClassName = JavaSourceParser.parseFullClassName(sourceCode);
        return compile(fullClassName, sourceCode, Object.class);
    }

    /**
     * Compiles Java source code taking the input of the class and a target interface/type.
     * Automatically infers package and class name from the source code declaration.
     *
     * @param sourceCode Java source code string
     * @param targetType Target interface or class to cast/validate against
     * @param <T>        Target type parameter
     * @return CompilationResult containing loaded Class cast to T
     */
    default <T> CompilationResult<T> compile(String sourceCode, Class<T> targetType) {
        String fullClassName = JavaSourceParser.parseFullClassName(sourceCode);
        return compile(fullClassName, sourceCode, targetType);
    }

    /**
     * Compiles Java source code from a file Path.
     */
    default CompilationResult<Object> compile(Path sourceFile) {
        return compile(sourceFile, Object.class);
    }

    /**
     * Compiles Java source code from a file Path with an expected target type.
     */
    default <T> CompilationResult<T> compile(Path sourceFile, Class<T> targetType) {
        try {
            String sourceCode = Files.readString(sourceFile);
            return compile(sourceCode, targetType);
        } catch (IOException e) {
            throw new RuntimeException("Failed reading source file from path: " + sourceFile, e);
        }
    }

    /**
     * Compiles Java source code from a File.
     */
    default CompilationResult<Object> compile(File sourceFile) {
        return compile(sourceFile.toPath(), Object.class);
    }

    /**
     * Compiles Java source code from a File with an expected target type.
     */
    default <T> CompilationResult<T> compile(File sourceFile, Class<T> targetType) {
        return compile(sourceFile.toPath(), targetType);
    }

    /**
     * Compiles multiple source codes sequentially, retaining all compiled bytecodes in memory.
     */
    default Map<String, CompilationResult<Object>> compileAll(String... sourceCodes) {
        if (sourceCodes == null || sourceCodes.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, CompilationResult<Object>> results = new LinkedHashMap<>();
        for (String code : sourceCodes) {
            String name = JavaSourceParser.parseFullClassName(code);
            results.put(name, compile(name, code, Object.class));
        }
        return results;
    }

    /**
     * Compiles a collection of source codes sequentially, retaining all compiled bytecodes in memory.
     */
    default Map<String, CompilationResult<Object>> compileAll(Collection<String> sourceCodes) {
        if (sourceCodes == null || sourceCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        return compileAll(sourceCodes.toArray(new String[0]));
    }

    /**
     * Compiles a batch of classes in a single compilation task, allowing mutual or circular dependencies.
     */
    default Map<String, CompilationResult<Object>> compileBatch(Map<String, String> classesByName) {
        Map<String, CompilationResult<Object>> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : classesByName.entrySet()) {
            results.put(entry.getKey(), compile(entry.getKey(), entry.getValue(), Object.class));
        }
        return results;
    }

    /**
     * Adds an additional classpath location (JAR file or directory) to the compilation path.
     */
    default void addClasspath(String path) {
        if (path != null && !path.isBlank()) {
            addClasspath(Path.of(path));
        }
    }

    /**
     * Adds an additional classpath directory or JAR to the compilation path.
     */
    void addClasspath(Path path);

    /**
     * Adds an additional classpath directory or JAR to the compilation path.
     */
    default void addClasspath(File file) {
        if (file != null) {
            addClasspath(file.toPath());
        }
    }

    /**
     * Returns the configured compilation classpath paths.
     */
    List<Path> getCompilationPaths();

    /**
     * Returns the ClassLoader used for dependency resolution and parent delegation.
     */
    ClassLoader getClassLoader();

    /**
     * Sets a custom ClassLoader for resolving dependent classes.
     */
    void setClassLoader(ClassLoader classLoader);

    /**
     * Loads a class by name from the compiler's memory and tiered storage.
     */
    Class<?> loadClass(String className) throws ClassNotFoundException;

    /**
     * Returns the TieredBytecodeRegistry coordinating multi-tier persistence and lookup:
     * <b>ClassLoader &gt; Redis &gt; Filesystem &gt; DB</b>.
     */
    TieredBytecodeRegistry getTieredRegistry();

    /**
     * Sets the TieredBytecodeRegistry used by the compiler.
     */
    void setTieredRegistry(TieredBytecodeRegistry tieredRegistry);

    /**
     * Registers a pre-compiled bytecode directly into memory for dependent class resolution.
     */
    void registerCompiledClass(String className, byte[] bytecode);

    /**
     * Retrieves bytecode for a previously compiled class.
     */
    byte[] getCompiledBytecode(String className);

    /**
     * Returns all in-memory compiled class bytecodes.
     */
    Map<String, byte[]> getCompiledBytecodes();

    /**
     * Clears all cached compiled classes across cache and tiered registries.
     */
    void clearCache();
}
