package umrao.compile.jit;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import umrao.compile.engine.MemoryClassLoader;

import java.util.Map;

/**
 * Javassist Runtime Compiler & Bytecode Manipulator:
 * Enables structural class manipulation and source-level bytecode injection at runtime.
 */
public class JavassistRuntimeCompiler {

    private final ClassPool classPool;

    public JavassistRuntimeCompiler() {
        this.classPool = ClassPool.getDefault();
    }

    /**
     * Dynamically creates a class implementing targetInterface with custom method body.
     */
    @SuppressWarnings("unchecked")
    public <T> T createImplementation(String fullClassName, Class<T> targetInterface, String methodSource) {
        try {
            CtClass interfaceClass = classPool.get(targetInterface.getName());
            CtClass newClass = classPool.makeClass(fullClassName);
            newClass.addInterface(interfaceClass);

            CtMethod method = CtNewMethod.make(methodSource, newClass);
            newClass.addMethod(method);

            byte[] bytecode = newClass.toBytecode();
            newClass.detach();

            MemoryClassLoader loader = new MemoryClassLoader(
                    targetInterface.getClassLoader(),
                    Map.of(fullClassName, bytecode)
            );

            Class<?> clazz = loader.loadClass(fullClassName);
            return (T) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Javassist runtime compilation failed for " + fullClassName, e);
        }
    }
}
