package umrao.compile.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

/**
 * Result of a dynamic compilation attempt.
 *
 * @param <T> The target interface or superclass type expected from the compiled class.
 */
public class CompilationResult<T> {

    private final boolean success;
    private final Class<? extends T> compiledClass;
    private final List<CompilationDiagnostic> diagnostics;
    private final long compilationTimeMs;
    private final boolean cached;

    public CompilationResult(boolean success,
                             Class<? extends T> compiledClass,
                             List<CompilationDiagnostic> diagnostics,
                             long compilationTimeMs,
                             boolean cached) {
        this.success = success;
        this.compiledClass = compiledClass;
        this.diagnostics = diagnostics != null ? Collections.unmodifiableList(diagnostics) : Collections.emptyList();
        this.compilationTimeMs = compilationTimeMs;
        this.cached = cached;
    }

    public static <T> CompilationResult<T> failure(List<CompilationDiagnostic> diagnostics, long durationMs) {
        return new CompilationResult<>(false, null, diagnostics, durationMs, false);
    }

    public static <T> CompilationResult<T> success(Class<? extends T> clazz,
                                                  List<CompilationDiagnostic> diagnostics,
                                                  long durationMs,
                                                  boolean cached) {
        return new CompilationResult<>(true, clazz, diagnostics, durationMs, cached);
    }

    public boolean isSuccess() {
        return success;
    }

    public Class<? extends T> getCompiledClass() {
        return compiledClass;
    }

    public List<CompilationDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public long getCompilationTimeMs() {
        return compilationTimeMs;
    }

    public boolean isCached() {
        return cached;
    }

    /**
     * Instantiates the dynamically compiled class using its default no-arg constructor.
     *
     * @return A newly created instance cast to T.
     */
    public T newInstance() {
        return newInstance(new Class<?>[0], new Object[0]);
    }

    /**
     * Instantiates the dynamically compiled class using a constructor matching parameter types.
     */
    public T newInstance(Class<?>[] paramTypes, Object[] args) {
        if (!success || compiledClass == null) {
            throw new IllegalStateException("Cannot instantiate class from a failed compilation.");
        }
        try {
            Constructor<? extends T> constructor = compiledClass.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Constructor not found for parameters on " + compiledClass.getName(), e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to instantiate " + compiledClass.getName(), e);
        }
    }
}
