package umrao.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.engine.JavaSourceParser;
import umrao.compile.engine.MemoryClassLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobustCompilationApiTest {

    private DynamicCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new InMemoryCompiler();
    }

    @Test
    @DisplayName("Should compile class taking ONLY source code input (auto-inferred package and class name)")
    void testCompileTakingOnlySourceCode() {
        String code = """
                package umrao.test.generated;

                public class GreeterService {
                    public String greet(String name) {
                        return "Hello, " + name + "!";
                    }

                    public static int calculate(int x, int y) {
                        return x + y;
                    }
                }
                """;

        CompilationResult<Object> result = compiler.compile(code);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompiledClass().getName()).isEqualTo("umrao.test.generated.GreeterService");

        // Test dynamic invocation helper
        Object greeting = result.invoke("greet", "World");
        assertThat(greeting).isEqualTo("Hello, World!");

        // Test static method invocation helper
        Object sum = result.invokeStatic("calculate", 20, 22);
        assertThat(sum).isEqualTo(42);
    }

    @Test
    @DisplayName("Should extract class declarations correctly ignoring keywords in comments and strings")
    void testSourceCodeParserRobustness() {
        String trickyCode = """
                // package fake.pkg;
                /* public class FakeClass {} */
                package umrao.real;

                public class RealService {
                    private String dummy = "package not.a.real.pkg; public class FakeInsideString {}";
                }
                """;

        assertThat(JavaSourceParser.parsePackageName(trickyCode)).isEqualTo("umrao.real");
        assertThat(JavaSourceParser.parseSimpleClassName(trickyCode)).isEqualTo("RealService");
        assertThat(JavaSourceParser.parseFullClassName(trickyCode)).isEqualTo("umrao.real.RealService");

        CompilationResult<Object> result = compiler.compile(trickyCode);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompiledClass().getName()).isEqualTo("umrao.real.RealService");
    }

    @Test
    @DisplayName("Should compile record and enum taking only source code")
    void testCompileRecordAndEnumTakingOnlySource() {
        String recordCode = """
                package umrao.test.model;
                public record Coordinate(int x, int y) {}
                """;
        CompilationResult<Object> recordResult = compiler.compile(recordCode);
        assertThat(recordResult.isSuccess()).isTrue();
        assertThat(recordResult.getCompiledClass().isRecord()).isTrue();

        Object coord = recordResult.newInstance(10, 20);
        assertThat(recordResult.invokeOn(coord, "x")).isEqualTo(10);
        assertThat(recordResult.invokeOn(coord, "y")).isEqualTo(20);

        String enumCode = """
                package umrao.test.model;
                public enum Priority { LOW, MEDIUM, HIGH }
                """;
        CompilationResult<Object> enumResult = compiler.compile(enumCode);
        assertThat(enumResult.isSuccess()).isTrue();
        assertThat(enumResult.getCompiledClass().isEnum()).isTrue();
    }

    @Test
    @DisplayName("Should resolve dependent class from previously compiled in-memory classes across sequential compiles")
    void testDependentClassesPickedFromPreviouslyCompiledClasses() {
        // Step 1: Compile Address (model)
        String addressCode = """
                package umrao.domain;

                public class Address {
                    private final String city;
                    public Address(String city) { this.city = city; }
                    public String getCity() { return city; }
                }
                """;
        CompilationResult<Object> addressResult = compiler.compile(addressCode);
        assertThat(addressResult.isSuccess()).isTrue();

        // Step 2: Compile Customer (model) which depends on Address
        String customerCode = """
                package umrao.domain;

                public class Customer {
                    private final String name;
                    private final Address address;

                    public Customer(String name, Address address) {
                        this.name = name;
                        this.address = address;
                    }
                    public String getName() { return name; }
                    public Address getAddress() { return address; }
                }
                """;
        CompilationResult<Object> customerResult = compiler.compile(customerCode);
        assertThat(customerResult.isSuccess()).isTrue();

        // Step 3: Compile ShippingService which depends on Customer and Address
        String shippingCode = """
                package umrao.domain.service;

                import umrao.domain.Customer;
                import umrao.domain.Address;

                public class ShippingService {
                    public String ship(Customer customer) {
                        return "Shipping parcel to " + customer.getName() + " in " + customer.getAddress().getCity();
                    }
                }
                """;
        CompilationResult<Object> shippingResult = compiler.compile(shippingCode);
        assertThat(shippingResult.isSuccess()).isTrue();

        // Execute all 3 compiled classes together
        Object address = addressResult.newInstance("London");
        Object customer = customerResult.newInstance("Alice", address);
        Object shippingService = shippingResult.newInstance();

        Object shipment = shippingResult.invokeOn(shippingService, "ship", customer);
        assertThat(shipment).isEqualTo("Shipping parcel to Alice in London");
    }

    @Test
    @DisplayName("Should compile batch of mutually dependent classes in a single compilation task")
    void testBatchCompilationMutuallyDependentClasses() {
        String nodeACode = """
                package umrao.graph;

                public class NodeA {
                    private NodeB peer;
                    public void setPeer(NodeB peer) { this.peer = peer; }
                    public NodeB getPeer() { return peer; }
                    public String name() { return "NodeA"; }
                }
                """;

        String nodeBCode = """
                package umrao.graph;

                public class NodeB {
                    private NodeA peer;
                    public void setPeer(NodeA peer) { this.peer = peer; }
                    public NodeA getPeer() { return peer; }
                    public String name() { return "NodeB"; }
                }
                """;

        Map<String, CompilationResult<Object>> batchResults = compiler.compileBatch(Map.of(
                "umrao.graph.NodeA", nodeACode,
                "umrao.graph.NodeB", nodeBCode
        ));

        assertThat(batchResults).hasSize(2);
        CompilationResult<Object> resA = batchResults.get("umrao.graph.NodeA");
        CompilationResult<Object> resB = batchResults.get("umrao.graph.NodeB");

        assertThat(resA.isSuccess()).isTrue();
        assertThat(resB.isSuccess()).isTrue();

        Object a = resA.newInstance();
        Object b = resB.newInstance();

        resA.invokeOn(a, "setPeer", b);
        resB.invokeOn(b, "setPeer", a);

        Object bFromA = resA.invokeOn(a, "getPeer");
        assertThat(bFromA).isSameAs(b);
    }

    @Test
    @DisplayName("Should resolve dependency from custom ClassLoader")
    void testDependencyPickedFromClassLoader() {
        // Create an in-memory class in a custom ClassLoader
        String helperCode = """
                package umrao.external;
                public class CustomHelper {
                    public static String getSecret() { return "TOP_SECRET_42"; }
                }
                """;
        DynamicCompiler prepCompiler = new InMemoryCompiler();
        prepCompiler.compile(helperCode);
        byte[] helperBytecode = prepCompiler.getCompiledBytecode("umrao.external.CustomHelper");

        MemoryClassLoader customLoader = new MemoryClassLoader(
                getClass().getClassLoader(),
                Map.of("umrao.external.CustomHelper", helperBytecode)
        );

        // Configure a new compiler with this custom ClassLoader
        DynamicCompiler loaderAwareCompiler = new InMemoryCompiler(customLoader);

        String consumerCode = """
                package umrao.client;
                import umrao.external.CustomHelper;

                public class ClientApp {
                    public String fetch() {
                        return "Fetched: " + CustomHelper.getSecret();
                    }
                }
                """;

        CompilationResult<Object> clientResult = loaderAwareCompiler.compile(consumerCode);
        assertThat(clientResult.isSuccess()).isTrue();
        assertThat(clientResult.invoke("fetch")).isEqualTo("Fetched: TOP_SECRET_42");
    }

    @Test
    @DisplayName("Should resolve dependencies from configured compilation classpath")
    void testDependencyPickedFromCompilationPath(@TempDir Path tempDir) throws IOException {
        // Compile a class to disk in tempDir
        String libraryCode = """
                package umrao.lib;
                public class ExternalLibrary {
                    public static int compute(int v) { return v * 10; }
                }
                """;

        DynamicCompiler diskPrepCompiler = new InMemoryCompiler();
        diskPrepCompiler.compile(libraryCode);
        byte[] libBytes = diskPrepCompiler.getCompiledBytecode("umrao.lib.ExternalLibrary");

        Path libPackageDir = tempDir.resolve("umrao").resolve("lib");
        Files.createDirectories(libPackageDir);
        Files.write(libPackageDir.resolve("ExternalLibrary.class"), libBytes);

        // Create compiler with compilation path configured
        DynamicCompiler pathCompiler = InMemoryCompiler.builder()
                .addClasspath(tempDir)
                .build();

        String appCode = """
                package umrao.app;
                import umrao.lib.ExternalLibrary;

                public class App {
                    public int run(int x) {
                        return ExternalLibrary.compute(x) + 5;
                    }
                }
                """;

        CompilationResult<Object> appResult = pathCompiler.compile(appCode);
        assertThat(appResult.isSuccess()).isTrue();
        assertThat(appResult.invoke("run", 7)).isEqualTo(75);
    }

    @Test
    @DisplayName("Should support compiling from File and Path objects")
    void testCompileFromFileAndPath(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("SimpleFileClass.java");
        Files.writeString(file, """
                package umrao.fromfile;
                public class SimpleFileClass {
                    public String echo(String msg) { return msg; }
                }
                """);

        CompilationResult<Object> pathResult = compiler.compile(file);
        assertThat(pathResult.isSuccess()).isTrue();
        assertThat(pathResult.invoke("echo", "PathInput")).isEqualTo("PathInput");

        CompilationResult<Object> fileResult = compiler.compile(file.toFile());
        assertThat(fileResult.isSuccess()).isTrue();
        assertThat(fileResult.invoke("echo", "FileInput")).isEqualTo("FileInput");
    }

    @Test
    @DisplayName("Should handle constructor argument errors and missing methods gracefully")
    void testInvocationErrorHandling() {
        String code = """
                package umrao.test;
                public class StrictClass {
                    private final int number;
                    public StrictClass() { this(0); }
                    public StrictClass(int number) { this.number = number; }
                    public int getNumber() { return number; }
                }
                """;

        CompilationResult<Object> result = compiler.compile(code);

        // Constructor with invalid arguments
        assertThatThrownBy(() -> result.newInstance("NotAnInteger"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No matching constructor found");

        // Invoke non-existent method
        assertThatThrownBy(() -> result.invoke("nonExistentMethod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Method 'nonExistentMethod' matching arguments [] not found");
    }
}
