package umrao.compile.jit;

import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.std.Message;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Benchmark runner comparing all modern runtime compilation & JIT frameworks.
 */
public class JitCompilationBenchmark {

    public static class ByteBuddyDemoInterceptor {
        public void showMessage() {
            System.out.println("    -> Byte Buddy: Executed dynamically synthesized subclass.");
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("       RUNTIME COMPILATION & JIT COMPILER ECOSYSTEM BENCHMARK                   ");
        System.out.println("================================================================================");

        // 1. Standard Java Compiler API (javax.tools)
        benchmarkJavacInMemory();

        // 2. Janino Fast JIT Compiler
        benchmarkJanino();

        // 3. Byte Buddy Dynamic Synthesizer
        benchmarkByteBuddy();

        // 4. Javassist Runtime Compiler
        benchmarkJavassist();

        // 5. Apache Groovy JIT Engine
        benchmarkGroovy();

        // 6. MVEL Compiled Expression Engine
        benchmarkMvel();

        System.out.println("================================================================================");
        System.out.println("       ALL 6 MODERN RUNTIME & JIT COMPILATION ENGINES VERIFIED                  ");
        System.out.println("================================================================================");
    }

    private static void benchmarkJavacInMemory() {
        System.out.println("\n[1] Java Compiler API (javax.tools In-Memory):");
        DynamicCompiler compiler = new InMemoryCompiler();
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                public class JavacMsg implements Message {
                    public void showMessage() {
                        System.out.println("    -> Javac: Executed in-memory compiled bytecode.");
                    }
                }
                """;

        long start = System.nanoTime();
        Message msg = compiler.compile("umrao.generated.JavacMsg", code, Message.class).newInstance();
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Compile & Instantiate Time: %d ms%n", durationMs);
        msg.showMessage();
    }

    private static void benchmarkJanino() {
        System.out.println("\n[2] Janino Embedded JIT Compiler (Spark/Calcite Engine):");
        JaninoJitCompiler janino = new JaninoJitCompiler();
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                public class JaninoMsg implements Message {
                    public void showMessage() {
                        System.out.println("    -> Janino: Executed high-speed JIT compiled class.");
                    }
                }
                """;

        long start = System.nanoTime();
        Message msg = janino.compileAndInstantiate("umrao.generated.JaninoMsg", code, Message.class);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Compile & Instantiate Time: %d ms%n", durationMs);
        msg.showMessage();
    }

    private static void benchmarkByteBuddy() {
        System.out.println("\n[3] Byte Buddy Runtime Bytecode Synthesizer (Mockito/Hibernate Engine):");
        ByteBuddyRuntimeGenerator byteBuddy = new ByteBuddyRuntimeGenerator();

        long start = System.nanoTime();
        Message msg = byteBuddy.createProxy(Message.class, new ByteBuddyDemoInterceptor());
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Synthesis & Load Time: %d ms%n", durationMs);
        msg.showMessage();
    }

    private static void benchmarkJavassist() {
        System.out.println("\n[4] Javassist Dynamic Class Transformer:");
        JavassistRuntimeCompiler javassist = new JavassistRuntimeCompiler();

        long start = System.nanoTime();
        Message msg = javassist.createImplementation(
                "umrao.generated.JavassistMsg",
                Message.class,
                "public void showMessage() { System.out.println(\"    -> Javassist: Executed bytecode-injected class.\"); }"
        );
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Bytecode Injection & Load Time: %d ms%n", durationMs);
        msg.showMessage();
    }

    private static void benchmarkGroovy() {
        System.out.println("\n[5] Apache Groovy Dynamic JIT Engine:");
        GroovyJitCompiler groovy = new GroovyJitCompiler();
        String script = """
                package umrao.generated
                import umrao.std.Message
                import groovy.transform.CompileStatic
                
                @CompileStatic
                class GroovyMsg implements Message {
                    void showMessage() {
                        println "    -> Groovy: Executed @CompileStatic JIT bytecode."
                    }
                }
                """;

        long start = System.nanoTime();
        Message msg = groovy.compileAndInstantiate(script, Message.class);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("    Groovy JIT Parse & Load Time: %d ms%n", durationMs);
        msg.showMessage();
    }

    private static void benchmarkMvel() {
        System.out.println("\n[6] MVEL Compiled JIT Expression Engine:");
        MvelJitEngine mvel = new MvelJitEngine();
        String expression = "x * y + 10";

        long start = System.nanoTime();
        Serializable compiled = mvel.compile(expression);
        long compileMs = (System.nanoTime() - start) / 1_000_000;

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("x", 20);
        ctx.put("y", 5);

        long execStart = System.nanoTime();
        Object result = mvel.execute(compiled, ctx);
        long execMicros = (System.nanoTime() - execStart) / 1_000;

        System.out.printf("    MVEL Compile Time: %d ms, Execution Time: %d µs (Result: %s)%n", compileMs, execMicros, result);
        System.out.println("    -> MVEL: Evaluated compiled AST bytecode with zero reflection overhead.");
    }
}
