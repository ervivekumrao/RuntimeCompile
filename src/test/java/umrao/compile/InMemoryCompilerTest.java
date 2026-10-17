package umrao.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.api.CompilationException;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.security.SandboxedExecutor;
import umrao.compile.security.SecurityPolicy;
import umrao.compile.security.SecurityViolationException;
import umrao.std.Message;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCompilerTest {

    private DynamicCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);
    }

    @Test
    @DisplayName("Should successfully compile and execute dynamic class in memory")
    void testBasicCompilationAndExecution() {
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                
                public class Greeter implements Message {
                    @Override
                    public void showMessage() {
                        System.out.println("Hello from dynamic compilation!");
                    }
                }
                """;

        CompilationResult<Message> result = compiler.compile("umrao.generated.Greeter", code, Message.class);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompiledClass()).isNotNull();
        assertThat(result.isCached()).isFalse();

        Message greeter = result.newInstance();
        assertThat(greeter).isNotNull();
    }

    @Test
    @DisplayName("Should return cached compilation result when source code is identical")
    void testCompilationCaching() {
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                
                public class CachedGreeter implements Message {
                    @Override
                    public void showMessage() {}
                }
                """;

        CompilationResult<Message> first = compiler.compile("umrao.generated.CachedGreeter", code, Message.class);
        assertThat(first.isCached()).isFalse();

        CompilationResult<Message> second = compiler.compile("umrao.generated.CachedGreeter", code, Message.class);
        assertThat(second.isCached()).isTrue();
        assertThat(second.getCompiledClass()).isEqualTo(first.getCompiledClass());
    }

    @Test
    @DisplayName("Should collect compilation errors when syntax is invalid")
    void testCompilationSyntaxError() {
        String invalidCode = """
                package umrao.generated;
                public class BrokenClass {
                    invalid java syntax here !!!
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.BrokenClass", invalidCode, Object.class))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Compilation failed for class umrao.generated.BrokenClass");
    }

    @Test
    @DisplayName("Should block code executing system commands (RCE prevention)")
    void testSecurityRcePrevention() {
        String maliciousCode = """
                package umrao.generated;
                import umrao.std.Message;
                
                public class ExploitMessage implements Message {
                    @Override
                    public void showMessage() {
                        try {
                            Runtime.getRuntime().exec("whoami");
                        } catch (Exception ignored) {}
                    }
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.ExploitMessage", maliciousCode, Message.class))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Runtime.getRuntime");
    }

    @Test
    @DisplayName("Should block code initiating network sockets")
    void testSecurityNetworkBlocked() {
        String networkCode = """
                package umrao.generated;
                import umrao.std.Message;
                import java.net.Socket;
                
                public class NetworkMessage implements Message {
                    @Override
                    public void showMessage() {}
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.NetworkMessage", networkCode, Message.class))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("java.net");
    }

    @Test
    @DisplayName("Should block code using reflection to bypass access controls")
    void testSecurityReflectionBlocked() {
        String reflectionCode = """
                package umrao.generated;
                import umrao.std.Message;
                import java.lang.reflect.Method;
                
                public class ReflectionMessage implements Message {
                    @Override
                    public void showMessage() {}
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.ReflectionMessage", reflectionCode, Message.class))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("java.lang.reflect");
    }

    @Test
    @DisplayName("Should terminate infinite loop using SandboxedExecutor timeout")
    void testSandboxedExecutorTimeout() {
        SandboxedExecutor executor = new SandboxedExecutor(Duration.ofMillis(200));
        try {
            assertThatThrownBy(() -> executor.execute(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.onSpinWait();
                }
                return null;
            }))
            .isInstanceOf(SecurityViolationException.class)
            .hasMessageContaining("EXECUTION_TIMEOUT");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Should enforce target interface compatibility")
    void testTypeSafetyEnforcement() {
        String code = """
                package umrao.generated;
                
                public class IncompatibleClass {
                    public void doSomething() {}
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.IncompatibleClass", code, Message.class))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("does not implement or extend expected type");
    }

    @Test
    @DisplayName("Should create failure CompilationResult and disallow instantiation")
    void testCompilationResultFailure() {
        CompilationResult<Message> failureResult = CompilationResult.failure(Collections.emptyList(), 15);
        assertThat(failureResult.isSuccess()).isFalse();
        assertThat(failureResult.getCompiledClass()).isNull();
        assertThat(failureResult.getCompilationTimeMs()).isEqualTo(15);
        assertThatThrownBy(failureResult::newInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot instantiate class from a failed compilation");
    }
}
