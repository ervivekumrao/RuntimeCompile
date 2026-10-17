package umrao.compile.jit;

import groovy.lang.GroovyClassLoader;

/**
 * Apache Groovy JIT Engine:
 * Compiles dynamic script source into native JVM bytecode on the fly.
 * Supports @groovy.transform.CompileStatic for static JIT performance.
 */
public class GroovyJitCompiler {

    private final GroovyClassLoader classLoader;

    public GroovyJitCompiler() {
        this.classLoader = new GroovyClassLoader(GroovyJitCompiler.class.getClassLoader());
    }

    /**
     * Compiles a Groovy script implementing targetInterface into static JVM bytecode and instantiates it.
     */
    @SuppressWarnings("unchecked")
    public <T> T compileAndInstantiate(String scriptCode, Class<T> targetInterface) {
        try {
            Class<?> parsedClass = classLoader.parseClass(scriptCode);
            if (!targetInterface.isAssignableFrom(parsedClass)) {
                throw new IllegalArgumentException("Groovy script does not implement " + targetInterface.getName());
            }
            return (T) parsedClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Groovy JIT compilation failed: " + e.getMessage(), e);
        }
    }
}
