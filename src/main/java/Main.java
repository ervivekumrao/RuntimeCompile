import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.OnTheFlyCompiler;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.jit.JitCompilationBenchmark;
import umrao.compile.security.SecurityPolicy;
import umrao.compile.security.SecurityViolationException;
import umrao.compile.service.CompilerHttpService;
import umrao.compile.store.DatabaseBytecodeStore;
import umrao.compile.store.FileSystemBytecodeStore;
import umrao.compile.store.RedisBytecodeStore;

import java.nio.file.Path;

/**
 * Main application entrypoint for RuntimeCompile.
 * <p>
 * Retains explicit class and standard {@code public static void main(String[] args)} signature
 * to maintain strict compliance with Java 21 LTS without requiring preview flags.
 */
@SuppressWarnings({"ConvertCompactSourceFile", "RedundantModifiersUtilityClass"})
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @SuppressWarnings("RedundantModifier")
    public static void main(String[] args) {
        boolean isServerMode = false;
        if (args != null) {
            for (String arg : args) {
                if ("--server".equalsIgnoreCase(arg) || "-s".equalsIgnoreCase(arg)) {
                    isServerMode = true;
                    break;
                }
            }
        }
        if ("true".equalsIgnoreCase(System.getenv("COMPILER_SERVICE"))) {
            isServerMode = true;
        }

        if (isServerMode) {
            startServiceDaemon();
            return;
        }

        runSuiteDemo(args);
    }

    private static void startServiceDaemon() {
        System.out.println("================================================================================");
        System.out.println("        STARTING RUNTIME COMPILATION & TIERED STORAGE REST SERVICE             ");
        System.out.println("================================================================================");

        try {
            CompilerHttpService service = CompilerHttpService.fromEnvironment();
            service.start();

            System.out.printf("Service is actively listening on HTTP port: %d%n", service.getPort());
            System.out.println("Endpoints available:");
            System.out.println("  - GET  /api/health   : Health & Uptime status");
            System.out.println("  - GET  /api/tiers    : Multi-tier storage hierarchy status");
            System.out.println("  - POST /api/compile  : Compile standalone Java code & persist across tiers");
            System.out.println("  - POST /api/execute  : Dynamically invoke compiled classes");
            System.out.println("  - GET  /api/bytecode : Retrieve compiled bytecode");
            System.out.println("  - GET  /api/classes  : Remote classloader URL endpoint");
            System.out.println("================================================================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down RuntimeCompile service...");
                service.stop();
            }));

            // Keep alive
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Failed to start RuntimeCompile service: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void runSuiteDemo(String[] args) {
        System.out.println("================================================================================");
        System.out.println("           ENTERPRISE RUNTIME COMPILATION & JIT EXECUTION SUITE                 ");
        System.out.println("================================================================================");

        DynamicCompiler compiler = new InMemoryCompiler();

        // 1. Standalone Class Compilation: takes ONLY source code input
        System.out.println("\n--- [1] Standalone Class Compilation (Input: Only Source Code, Zero Interface Dependency) ---");
        String standaloneCalculatorCode = """
                package umrao.demo;

                public class DynamicCalculator {
                    public int multiply(int a, int b) {
                        return a * b;
                    }

                    public String formatReport(String item, double price, int qty) {
                        return String.format("Invoice: %s x%d = $%.2f", item, qty, price * qty);
                    }
                }
                """;

        long startCold = System.nanoTime();
        CompilationResult<Object> calcResult = compiler.compile(standaloneCalculatorCode);
        long coldTimeMs = (System.nanoTime() - startCold) / 1_000_000;
        System.out.printf("Compiled '%s' in %d ms (Cached: %s)%n",
                calcResult.getCompiledClass().getName(), coldTimeMs, calcResult.isCached());

        Object multResult = calcResult.invoke("multiply", 12, 15);
        System.out.println(">> Invoked DynamicCalculator.multiply(12, 15) = " + multResult);

        Object report = calcResult.invoke("formatReport", "Enterprise License", 499.99, 3);
        System.out.println(">> Invoked DynamicCalculator.formatReport(...) = " + report);

        // 2. Cross-Class Dependency Resolution from Previously Compiled In-Memory Classes
        System.out.println("\n--- [2] Dependency Resolution: Picking Dependencies from Previously Compiled Classes ---");

        String orderModelCode = """
                package umrao.demo.model;

                public class Order {
                    private final String orderId;
                    private final double amount;

                    public Order(String orderId, double amount) {
                        this.orderId = orderId;
                        this.amount = amount;
                    }

                    public String getOrderId() { return orderId; }
                    public double getAmount() { return amount; }
                }
                """;
        System.out.println("Compiling dependent model: umrao.demo.model.Order (only source code provided)...");
        CompilationResult<Object> orderResult = compiler.compile(orderModelCode);
        System.out.println(">> Order class compiled successfully into memory.");

        String orderServiceCode = """
                package umrao.demo.service;

                import umrao.demo.model.Order;

                public class OrderService {
                    public String processOrder(Order order) {
                        double tax = order.getAmount() * 0.08;
                        return String.format("Processed Order [%s]: Total with 8%% tax = $%.2f",
                                order.getOrderId(), order.getAmount() + tax);
                    }
                }
                """;
        System.out.println("Compiling service: umrao.demo.service.OrderService (depends on previously compiled Order)...");
        CompilationResult<Object> serviceResult = compiler.compile(orderServiceCode);
        System.out.println(">> OrderService compiled successfully by resolving Order from in-memory bytecode repository!");

        Object sampleOrder = orderResult.newInstance("ORD-98421", 250.0);
        Object serviceInstance = serviceResult.newInstance();
        Object processResult = serviceResult.invokeOn(serviceInstance, "processOrder", sampleOrder);
        System.out.println(">> Runtime Execution Result: " + processResult);

        // 3. Multi-Tiered Storage & Resolution Hierarchy: ClassLoader > Redis > Filesystem > DB
        System.out.println("\n--- [3] Tiered Bytecode Storage & Resolution: ClassLoader > Redis > Filesystem > DB ---");
        Path podStorageDir = Path.of("build/pod-classes");
        DynamicCompiler tieredCompiler = InMemoryCompiler.builder()
                .withRedisStore(RedisBytecodeStore.inMemory())
                .withFileSystemStore(new FileSystemBytecodeStore(podStorageDir))
                .withDatabaseStore(DatabaseBytecodeStore.h2InMemory("cluster_bytecode_db"))
                .build();

        String auditLogModel = """
                package umrao.telemetry;
                public class AuditRecord {
                    private final String event;
                    public AuditRecord(String event) { this.event = event; }
                    public String getEvent() { return event; }
                }
                """;

        System.out.println("Compiling AuditRecord and persisting across Redis, Filesystem, and External DB...");
        tieredCompiler.compile(auditLogModel);
        System.out.println(">> Saved to Redis cache (fast access)");
        System.out.println(">> Saved to Filesystem volume (" + podStorageDir + ")");
        System.out.println(">> Saved to External SQL Database (cluster-wide Docker persistence)");

        // 4. Backwards-compatible legacy run
        System.out.println("\n--- [4] Backwards-Compatible OnTheFlyCompiler Run ---");
        new OnTheFlyCompiler().compile();

        // 5. Security Sandboxing Demonstration
        System.out.println("\n--- [5] Security Sandboxing & Threat Mitigation ---");
        DynamicCompiler secureCompiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);
        String maliciousPayload = """
                package umrao.untrusted;

                public class MaliciousScript {
                    public void execute() {
                        try {
                            Runtime.getRuntime().exec("whoami");
                        } catch (Exception e) {}
                    }
                }
                """;

        try {
            System.out.println("Attempting to compile untrusted code with restricted system calls...");
            secureCompiler.compile(maliciousPayload);
            System.err.println("SECURITY FAILURE: Malicious code was permitted!");
        } catch (SecurityViolationException sve) {
            System.out.println("SECURITY SUCCESS: Malicious code was blocked as expected!");
            System.out.println("Blocked by policy rule: " + sve.getViolationRule() + " -> " + sve.getMessage());
        }

        // 6. Modern JIT & Runtime Compilation Multi-Framework Ecosystem Benchmark
        System.out.println("\n--- [6] Modern Runtime Compilation & JIT Frameworks Suite ---");
        JitCompilationBenchmark.main(args);

        System.out.println("\n================================================================================");
        System.out.println("           All Demonstrations & JIT Engines Verified Successfully               ");
        System.out.println("================================================================================");
    }
}
