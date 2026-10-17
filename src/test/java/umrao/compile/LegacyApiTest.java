package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.engine.InMemoryCompiler;
import umrao.std.Log;
import umrao.std.Print;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LegacyApiTest {

    @Test
    @DisplayName("Should execute OnTheFlyCompiler with custom InMemoryCompiler instance")
    void testOnTheFlyCompilerWithCustomEngine() {
        OnTheFlyCompiler compiler = new OnTheFlyCompiler(new InMemoryCompiler());
        assertThatCode(compiler::compile).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should verify Log and Print legacy utility classes")
    void testLegacyStdUtilities() {
        Log log = new Log();
        log.printLog();
        assertThat(log.getLogger()).isNotNull();

        Print print = new Print();
        assertThatCode(print::printMessage).doesNotThrowAnyException();
    }
}
