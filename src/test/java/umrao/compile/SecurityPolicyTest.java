package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.security.CodeSecurityValidator;
import umrao.compile.security.SecurityPolicy;
import umrao.compile.security.SecurityViolationException;
import umrao.std.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPolicyTest {

    @ParameterizedTest(name = "Strict policy should block import: {0}")
    @ValueSource(strings = {
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",
            "java.net.Socket",
            "java.net.URL",
            "java.io.File",
            "java.nio.file.Paths"
    })
    @DisplayName("Should reject restricted imports in dynamic code")
    void testBlockedImports(String restrictedImport) {
        CodeSecurityValidator validator = new CodeSecurityValidator(SecurityPolicy.strict());
        String code = "package umrao.test;\nimport " + restrictedImport + ";\npublic class Tmp {}";

        assertThatThrownBy(() -> validator.validate(code))
                .isInstanceOf(SecurityViolationException.class)
                .satisfies(e -> {
                    SecurityViolationException sve = (SecurityViolationException) e;
                    assertThat(sve.getViolationRule()).isEqualTo("BLOCKED_IMPORT");
                });
    }

    @ParameterizedTest(name = "Strict policy should block pattern: {0}")
    @ValueSource(strings = {
            "Runtime.getRuntime()",
            "ProcessBuilder",
            "System.exit",
            "System.load",
            "System.loadLibrary",
            "Unsafe"
    })
    @DisplayName("Should reject restricted code tokens and method calls")
    void testBlockedPatterns(String restrictedPattern) {
        CodeSecurityValidator validator = new CodeSecurityValidator(SecurityPolicy.strict());
        String code = "package umrao.test;\npublic class Tmp { void f() { " + restrictedPattern + "; } }";

        assertThatThrownBy(() -> validator.validate(code))
                .isInstanceOf(SecurityViolationException.class)
                .satisfies(e -> {
                    SecurityViolationException sve = (SecurityViolationException) e;
                    assertThat(sve.getViolationRule()).isEqualTo("RESTRICTED_API");
                });
    }

    @Test
    @DisplayName("Should reject code exceeding configured byte length limit")
    void testPayloadSizeLimit() {
        SecurityPolicy tinyPolicy = SecurityPolicy.builder()
                .maxSourceLength(50)
                .build();
        CodeSecurityValidator validator = new CodeSecurityValidator(tinyPolicy);

        String longCode = "public class A { // Very long comment that will certainly exceed the tiny limit }";

        assertThatThrownBy(() -> validator.validate(longCode))
                .isInstanceOf(SecurityViolationException.class)
                .satisfies(e -> {
                    SecurityViolationException sve = (SecurityViolationException) e;
                    assertThat(sve.getViolationRule()).isEqualTo("PAYLOAD_SIZE_LIMIT");
                });
    }

    @Test
    @DisplayName("Should allow compliant code without restrictions")
    void testPermittedCode() {
        CodeSecurityValidator validator = new CodeSecurityValidator(SecurityPolicy.strict());
        String validCode = """
                package umrao.test;
                import java.util.List;
                import java.util.ArrayList;
                
                public class CompliantClass {
                    public List<String> getNames() {
                        return new ArrayList<>();
                    }
                }
                """;

        assertThatCode(() -> validator.validate(validCode)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject compilation attempt via DynamicCompiler if policy is violated")
    void testCompilerIntegration() {
        DynamicCompiler compiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);
        String exploit = """
                package umrao.generated;
                import umrao.std.Message;
                public class RceMsg implements Message {
                    public void showMessage() {
                        System.exit(1);
                    }
                }
                """;

        assertThatThrownBy(() -> compiler.compile("umrao.generated.RceMsg", exploit, Message.class))
                .isInstanceOf(SecurityViolationException.class)
                .satisfies(e -> {
                    SecurityViolationException sve = (SecurityViolationException) e;
                    assertThat(sve.getViolationRule()).isEqualTo("RESTRICTED_API");
                });
    }
}
