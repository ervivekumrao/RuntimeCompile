package umrao.compile.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a dynamic compilation attempt with rich dynamic execution and invocation helpers.
 *
 * @param <T> The target interface or superclass type expected from the compiled class.
 */
public class CompilationResult<T> {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class
    );

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
     * Instantiates the dynamically compiled class with constructor arguments whose parameter types are inferred.
     */
    @SuppressWarnings("unchecked")
    public T newInstance(Object... args) {
        if (!success || compiledClass == null) {
            throw new IllegalStateException("Cannot instantiate class from a failed compilation.");
        }
        if (args == null || args.length == 0) {
            return newInstance(new Class<?>[0], new Object[0]);
        }

        for (Constructor<?> ctor : compiledClass.getDeclaredConstructors()) {
            if (isApplicable(ctor.getParameterTypes(), args)) {
                try {
                    ctor.setAccessible(true);
                    return (T) ctor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException("Failed invoking constructor for " + compiledClass.getName(), e);
                }
            }
        }

        throw new IllegalArgumentException(
                "No matching constructor found on " + compiledClass.getName() + " for arguments: " + Arrays.toString(args)
        );
    }

    /**
     * Instantiates the dynamically compiled class using explicit constructor parameter types.
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
            throw new IllegalArgumentException("Constructor not found for parameters on " + compiledClass.getName(), e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to instantiate " + compiledClass.getName(), e);
        }
    }

    /**
     * Instantiates a new instance of the compiled class using default constructor and invokes the specified instance method.
     */
    public Object invoke(String methodName, Object... args) {
        Object instance = newInstance();
        return invokeOn(instance, methodName, args);
    }

    /**
     * Invokes the specified instance method on an existing instance of the compiled class.
     */
    public Object invokeOn(Object target, String methodName, Object... args) {
        if (!success || compiledClass == null) {
            throw new IllegalStateException("Cannot invoke method on a failed compilation.");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target object must not be null.");
        }

        Method method = findMethod(methodName, false, args);
        try {
            method.setAccessible(true);
            return method.invoke(target, args != null ? args : new Object[0]);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Exception thrown while invoking method '" + methodName + "': " + cause.getMessage(), cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access denied when invoking method '" + methodName + "' on " + compiledClass.getName(), e);
        }
    }

    /**
     * Invokes the specified static method on the compiled class.
     */
    public Object invokeStatic(String methodName, Object... args) {
        if (!success || compiledClass == null) {
            throw new IllegalStateException("Cannot invoke static method on a failed compilation.");
        }

        Method method = findMethod(methodName, true, args);
        try {
            method.setAccessible(true);
            return method.invoke(null, args != null ? args : new Object[0]);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Exception thrown while invoking static method '" + methodName + "': " + cause.getMessage(), cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access denied when invoking static method '" + methodName + "' on " + compiledClass.getName(), e);
        }
    }

    private Method findMethod(String methodName, boolean staticExpected, Object[] args) {
        Object[] actualArgs = args != null ? args : new Object[0];

        for (Method m : compiledClass.getMethods()) {
            if (m.getName().equals(methodName)) {
                if (staticExpected && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (isApplicable(m.getParameterTypes(), actualArgs)) {
                    return m;
                }
            }
        }

        // Also check declared private/protected methods
        for (Method m : compiledClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                if (staticExpected && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (isApplicable(m.getParameterTypes(), actualArgs)) {
                    return m;
                }
            }
        }

        throw new IllegalArgumentException(
                String.format("Method '%s' matching arguments %s not found on class %s",
                        methodName, Arrays.toString(actualArgs), compiledClass.getName())
        );
    }

    private static boolean isApplicable(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];
            Object arg = args[i];

            if (arg == null) {
                if (paramType.isPrimitive()) {
                    return false;
                }
                continue;
            }

            if (paramType.isPrimitive()) {
                Class<?> wrapper = PRIMITIVE_TO_WRAPPER.get(paramType);
                if (wrapper == null || !wrapper.isInstance(arg)) {
                    return false;
                }
            } else if (!paramType.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }
}
