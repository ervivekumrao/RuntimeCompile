package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.engine.MemoryClassLoader;
import umrao.std.Message;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryClassLoaderTest {

    @Test
    @DisplayName("Should load class directly from byte array in memory")
    void testDirectBytecodeLoading() throws Exception {
        InMemoryCompiler compiler = new InMemoryCompiler();
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                public class DirectBytecode implements Message {
                    public void showMessage() {}
                }
                """;

        Class<? extends Message> compiled = compiler.compile("umrao.generated.DirectBytecode", code, Message.class).getCompiledClass();
        assertThat(compiled).isNotNull();

        // MemoryClassLoader should delegate to parent for standard classes
        MemoryClassLoader loader = new MemoryClassLoader(compiled.getClassLoader());
        Class<?> stringClass = loader.loadClass("java.lang.String");
        assertThat(stringClass).isEqualTo(String.class);
    }

    @Test
    @DisplayName("Should allow independent definitions of the same class name across isolated loaders")
    void testClassIsolationAcrossLoaders() throws Exception {
        InMemoryCompiler compiler = new InMemoryCompiler();
        String codeV1 = """
                package umrao.generated;
                public class VersionedClass {
                    public String getVersion() { return "v1"; }
                }
                """;
        String codeV2 = """
                package umrao.generated;
                public class VersionedClass {
                    public String getVersion() { return "v2"; }
                }
                """;

        Class<?> classV1 = compiler.compile("umrao.generated.VersionedClass", codeV1).getCompiledClass();

        // Clear compiler cache to recompile the same class name with different implementation
        compiler.clearCache();
        Class<?> classV2 = compiler.compile("umrao.generated.VersionedClass", codeV2).getCompiledClass();

        assertThat(classV1).isNotSameAs(classV2);
        assertThat(classV1.getClassLoader()).isNotSameAs(classV2.getClassLoader());

        Object instanceV1 = classV1.getDeclaredConstructor().newInstance();
        Object instanceV2 = classV2.getDeclaredConstructor().newInstance();

        String resV1 = (String) classV1.getMethod("getVersion").invoke(instanceV1);
        String resV2 = (String) classV2.getMethod("getVersion").invoke(instanceV2);

        assertThat(resV1).isEqualTo("v1");
        assertThat(resV2).isEqualTo("v2");
    }

    @Test
    @DisplayName("Should throw ClassNotFoundException when class is absent from bytecodes and parent")
    void testClassNotFound() {
        MemoryClassLoader loader = new MemoryClassLoader(getClass().getClassLoader(), Map.of());
        assertThatThrownBy(() -> loader.loadClass("non.existent.Class"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("Should store and define bytecode via addBytecode")
    void testAddBytecode() {
        MemoryClassLoader loader = new MemoryClassLoader(getClass().getClassLoader());
        byte[] dummyBytecode = new byte[]{1, 2, 3};
        loader.addBytecode("com.test.Dummy", dummyBytecode);
        assertThatThrownBy(() -> loader.loadClass("com.test.Dummy"))
                .isInstanceOf(ClassFormatError.class);
    }
}
