package umrao.compile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.api.CompilationDiagnostic;
import umrao.compile.api.CompilationException;
import umrao.compile.api.CompilationResult;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.security.SecurityPolicy;
import umrao.std.Log;
import umrao.std.Message;
import umrao.std.Print;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level dynamic compiler maintaining backwards-compatible API with the original demo,
 * backed by the enterprise in-memory dynamic compilation engine and SLF4J logging.
 */
public class OnTheFlyCompiler extends Log {

    private static final Logger log = LoggerFactory.getLogger(OnTheFlyCompiler.class);
    private final InMemoryCompiler inMemoryCompiler;

    public OnTheFlyCompiler() {
        this.inMemoryCompiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);
    }

    public OnTheFlyCompiler(InMemoryCompiler inMemoryCompiler) {
        this.inMemoryCompiler = inMemoryCompiler;
    }

    /**
     * Compiles and executes the MessageImpl class dynamically in-memory.
     */
    public void compile() {
        String javaCode = getJavaCodeFromFile().toString();

        log.info("Beginning dynamic in-memory compilation of umrao.generated.MessageImpl...");

        try {
            CompilationResult<Message> result = inMemoryCompiler.compile(
                    "umrao.generated.MessageImpl",
                    javaCode,
                    Message.class
            );

            log.info("MessageImpl class compilation Success (Time: {}ms).", result.getCompilationTimeMs());

            Print print = new Print();
            print.printMessage();
            printLog();

            // Instantiate dynamic class instance
            Message msg = result.newInstance();

            // Run overridden showMessage method
            msg.showMessage();

        } catch (CompilationException exp) {
            log.error("Compilation failed with errors:");
            for (CompilationDiagnostic diagnostic : exp.getDiagnostics()) {
                log.error("  -> {}", diagnostic);
            }
        } catch (Exception exp) {
            log.error("Execution Error: {}", exp.getMessage(), exp);
        }
    }

    /**
     * Resolves Java code from classpath resources or standard source locations.
     */
    public static StringBuilder getJavaCodeFromFile() {
        // 1. Try classpath resource
        try (InputStream is = OnTheFlyCompiler.class.getResourceAsStream("/JavaCode.txt")) {
            if (is != null) {
                return new StringBuilder(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.warn("Failed to read /JavaCode.txt from classpath: {}", e.getMessage());
        }

        // 2. Try file system paths
        String[] candidatePaths = {
                "src/main/resources/JavaCode.txt",
                "src/JavaCode.txt",
                "JavaCode.txt"
        };

        for (String candidate : candidatePaths) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                try {
                    return new StringBuilder(Files.readString(Path.of(candidate)));
                } catch (IOException e) {
                    log.warn("Failed reading candidate file {}: {}", candidate, e.getMessage());
                }
            }
        }

        // 3. Fallback to default in-memory template
        return getJavaCode();
    }

    /**
     * Fallback template code generator.
     */
    public static StringBuilder getJavaCode() {
        StringBuilder builder = new StringBuilder();
        builder.append("package umrao.generated;\n\n");
        builder.append("import umrao.std.Message;\n\n");
        builder.append("public class MessageImpl implements Message {\n");
        builder.append("    public void showMessage() {\n");
        builder.append("        System.out.println(\"Message from MessageImpl class that is runtime generated, compiled and class loaded.\");\n");
        builder.append("    }\n");
        builder.append("}\n");
        return builder;
    }
}
