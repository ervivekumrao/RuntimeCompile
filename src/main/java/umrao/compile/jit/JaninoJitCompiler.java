package umrao.compile.jit;

import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.SimpleCompiler;

/**
 * Janino Embedded JIT Compiler:
 * Ultra-fast embedded compiler widely used in big-data engines (Apache Spark, Presto, Calcite)
 * for runtime query JIT generation and high-throughput class compilation.
 */
public class JaninoJitCompiler {

    /**
     * Compiles Java source code directly into JVM bytecode in-memory in milliseconds.
     */
    @SuppressWarnings("unchecked")
    public <T> T compileAndInstantiate(String className, String sourceCode, Class<T> targetInterface) {
        try {
            SimpleCompiler compiler = new SimpleCompiler();
            compiler.setParentClassLoader(targetInterface.getClassLoader());
            compiler.cook(sourceCode);

            Class<?> clazz = compiler.getClassLoader().loadClass(className);
            return (T) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Janino runtime compilation failed for " + className, e);
        }
    }

    /**
     * Evaluates a dynamic Java expression in-memory without compiling a full class.
     * Example: evaluateExpression("x * 2 + y", Integer.class, new String[]{"x", "y"}, new Class[]{int.class, int.class}, new Object[]{10, 5}) -> 25
     */
    public Object evaluateExpression(String expression,
                                     Class<?> returnType,
                                     String[] parameterNames,
                                     Class<?>[] parameterTypes,
                                     Object[] argumentValues) {
        try {
            ExpressionEvaluator evaluator = new ExpressionEvaluator();
            evaluator.setParameters(parameterNames, parameterTypes);
            evaluator.setExpressionType(returnType);
            evaluator.cook(expression);
            return evaluator.evaluate(argumentValues);
        } catch (Exception e) {
            throw new RuntimeException("Janino expression evaluation failed for: " + expression, e);
        }
    }
}
