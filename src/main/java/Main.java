import umrao.compile.OnTheFlyCompiler;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.jit.JitCompilationBenchmark;
import umrao.compile.security.SecurityPolicy;
import umrao.compile.security.SecurityViolationException;
import umrao.std.Message;

public class Main {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("           ENTERPRISE RUNTIME COMPILATION & JIT EXECUTION SUITE                 ");
        System.out.println("================================================================================\n");

        // 1. Backwards-compatible legacy run (in-memory without ClassLoader bugs)
        System.out.println("--- [1] Backwards-Compatible OnTheFlyCompiler Run ---");
        new OnTheFlyCompiler().compile();

        // 2. High-Performance In-Memory Dynamic Compilation & Caching Demonstration
        System.out.println("\n--- [2] Advanced In-Memory Engine & Cache Benchmark ---");
        DynamicCompiler compiler = new InMemoryCompiler();

        String dynamicCode = """
                package umrao.generated;
                import umrao.std.Message;
                
                public class DynamicGreeter implements Message {
                    @Override
                    public void showMessage() {
                        System.out.println(">> Greetings from dynamically compiled DynamicGreeter in memory!");
                    }
                }
                """;

        // First compile (Cold run)
        long start1 = System.nanoTime();
        CompilationResult<Message> res1 = compiler.compile("umrao.generated.DynamicGreeter", dynamicCode, Message.class);
        long time1 = (System.nanoTime() - start1) / 1_000_000;
        System.out.printf("Cold Compilation: %d ms (Cached: %s)%n", time1, res1.isCached());
        res1.newInstance().showMessage();

        // Second compile (Warm / Cached run)
        long start2 = System.nanoTime();
        CompilationResult<Message> res2 = compiler.compile("umrao.generated.DynamicGreeter", dynamicCode, Message.class);
        long time2 = (System.nanoTime() - start2) / 1_000_000;
        System.out.printf("Cached Lookup:    %d ms (Cached: %s)%n", time2, res2.isCached());
        res2.newInstance().showMessage();

        // 3. Security Sandboxing Demonstration
        System.out.println("\n--- [3] Security Sandboxing & Threat Mitigation ---");
        DynamicCompiler secureCompiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);
        String maliciousPayload = """
                package umrao.generated;
                import umrao.std.Message;
                
                public class MaliciousCode implements Message {
                    @Override
                    public void showMessage() {
                        try {
                            Runtime.getRuntime().exec("whoami");
                        } catch (Exception e) {}
                    }
                }
                """;

        try {
            System.out.println("Attempting to compile untrusted code with restricted API calls...");
            secureCompiler.compile("umrao.generated.MaliciousCode", maliciousPayload, Message.class);
            System.err.println("SECURITY FAILURE: Malicious code was permitted!");
        } catch (SecurityViolationException sve) {
            System.out.println("SECURITY SUCCESS: Malicious code was blocked as expected!");
            System.out.println("Blocked by policy: " + sve.getMessage());
        }

        // 4. Modern JIT & Runtime Compilation Multi-Framework Ecosystem Benchmark
        System.out.println("\n--- [4] Modern Runtime Compilation & JIT Frameworks Suite ---");
        JitCompilationBenchmark.main(args);

        System.out.println("\n================================================================================");
        System.out.println("           All Demonstrations & JIT Engines Verified Successfully               ");
        System.out.println("================================================================================");
    }
}
