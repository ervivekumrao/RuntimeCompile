package umrao.compile.jit;

import org.mvel2.MVEL;

import java.io.Serializable;
import java.util.Map;

/**
 * MVEL JIT Expression Engine:
 * Compiles dynamic expressions into optimized bytecode structures for sub-microsecond repeated evaluations.
 */
public class MvelJitEngine {

    /**
     * Compiles an expression string into a compiled executable bytecode structure.
     */
    public Serializable compile(String expression) {
        return MVEL.compileExpression(expression);
    }

    /**
     * Evaluates a pre-compiled expression against runtime context variables.
     */
    public Object execute(Serializable compiledExpression, Map<String, Object> context) {
        return MVEL.executeExpression(compiledExpression, context);
    }

    /**
     * One-shot compilation and evaluation.
     */
    public Object eval(String expression, Map<String, Object> context) {
        return MVEL.eval(expression, context);
    }
}
