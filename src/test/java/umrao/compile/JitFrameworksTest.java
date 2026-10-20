package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.jit.*;
import umrao.std.Message;

import java.io.Serializable;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JitFrameworksTest {

    public static class TestInterceptor implements Message {
        private final boolean[] called;

        public TestInterceptor(boolean[] called) {
            this.called = called;
        }

        @Override
        public void showMessage() {
            called[0] = true;
        }
    }

    @Test
    @DisplayName("Janino: Should compile Java source class and evaluate expressions in-memory")
    void testJaninoJitCompiler() {
        JaninoJitCompiler janino = new JaninoJitCompiler();
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                public class JaninoTest implements Message {
                    public void showMessage() {}
                }
                """;

        Message msg = janino.compileAndInstantiate("umrao.generated.JaninoTest", code, Message.class);
        assertThat(msg).isNotNull();

        Object mathResult = janino.evaluateExpression(
                "a * b + 5",
                Integer.class,
                new String[]{"a", "b"},
                new Class<?>[]{int.class, int.class},
                new Object[]{10, 3}
        );
        assertThat(mathResult).isEqualTo(35);

        // Test bytecode generation
        Map<String, byte[]> bytecodes = janino.compileToBytecodes("umrao.generated.JaninoTest", code);
        assertThat(bytecodes).containsKey("umrao.generated.JaninoTest");
        assertThat(bytecodes.get("umrao.generated.JaninoTest")).isNotEmpty();
    }

    @Test
    @DisplayName("Byte Buddy: Should dynamically synthesize class implementing Message interface")
    void testByteBuddySynthesis() {
        ByteBuddyRuntimeGenerator byteBuddy = new ByteBuddyRuntimeGenerator();
        final boolean[] called = {false};

        Message msg = byteBuddy.createProxy(Message.class, new TestInterceptor(called));

        assertThat(msg).isNotNull();
        msg.showMessage();
        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("Javassist: Should inject bytecode and instantiate implementation at runtime")
    void testJavassistCompiler() {
        JavassistRuntimeCompiler javassist = new JavassistRuntimeCompiler();
        Message msg = javassist.createImplementation(
                "umrao.generated.JavassistTest",
                Message.class,
                "public void showMessage() {}"
        );

        assertThat(msg).isNotNull();
        msg.showMessage();
    }

    @Test
    @DisplayName("Groovy: Should compile static JIT script implementing Message interface and bytecodes")
    void testGroovyJitCompiler() {
        GroovyJitCompiler groovy = new GroovyJitCompiler();
        String script = """
                package umrao.generated
                import umrao.std.Message
                import groovy.transform.CompileStatic
                
                @CompileStatic
                class GroovyTest implements Message {
                    void showMessage() {}
                }
                """;

        Message msg = groovy.compileAndInstantiate(script, Message.class);
        assertThat(msg).isNotNull();
        msg.showMessage();

        // Test bytecode generation
        Map<String, byte[]> bytecodes = groovy.compileToBytecodes("umrao.generated.GroovyTest", script);
        assertThat(bytecodes).containsKey("umrao.generated.GroovyTest");
        assertThat(bytecodes.get("umrao.generated.GroovyTest")).isNotEmpty();
    }

    @Test
    @DisplayName("MVEL: Should compile expression to bytecode and evaluate with context")
    void testMvelJitEngine() {
        MvelJitEngine mvel = new MvelJitEngine();
        Serializable compiled = mvel.compile("price * quantity * (1 - discount)");

        Map<String, Object> ctx = Map.of(
                "price", 100.0,
                "quantity", 3,
                "discount", 0.10
        );

        Object result = mvel.execute(compiled, ctx);
        assertThat(result).isEqualTo(270.0);

        Object directResult = mvel.eval("price * quantity", ctx);
        assertThat(directResult).isEqualTo(300.0);
    }

    @Test
    @DisplayName("JitEngineType: Should expose descriptive metadata for each supported runtime engine")
    void testJitEngineTypeMetadata() {
        for (JitEngineType type : JitEngineType.values()) {
            assertThat(type.getDisplayName()).isNotBlank();
            assertThat(type.getDescription()).isNotBlank();
        }
        assertThat(JitEngineType.valueOf("JANINO")).isEqualTo(JitEngineType.JANINO);
    }
}
