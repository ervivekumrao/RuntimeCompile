package umrao.compile.api;

/**
 * High-performance, in-memory, thread-safe dynamic Java compiler interface.
 */
public interface DynamicCompiler {

    /**
     * Compiles Java source code into bytecode in-memory, loads it into an isolated classloader,
     * and validates that the resulting class implements or extends the specified target type.
     *
     * @param fullClassName Fully qualified class name (e.g. "umrao.generated.MessageImpl")
     * @param sourceCode    Java source code string
     * @param targetType    Interface or class that the compiled class is expected to implement/extend
     * @param <T>           Target type parameter
     * @return CompilationResult containing diagnostics and the loaded Class
     * @throws CompilationException if compilation fails
     */
    <T> CompilationResult<T> compile(String fullClassName, String sourceCode, Class<T> targetType);

    /**
     * Compiles Java source code into bytecode in-memory and returns the loaded Class as Object.
     */
    default CompilationResult<Object> compile(String fullClassName, String sourceCode) {
        return compile(fullClassName, sourceCode, Object.class);
    }

    /**
     * Clears all cached compiled classes.
     */
    void clearCache();
}
