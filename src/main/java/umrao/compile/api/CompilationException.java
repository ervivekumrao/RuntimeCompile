package umrao.compile.api;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when dynamic Java source code compilation fails.
 */
public class CompilationException extends RuntimeException {

    private final List<CompilationDiagnostic> diagnostics;

    public CompilationException(String message, List<CompilationDiagnostic> diagnostics) {
        super(buildErrorMessage(message, diagnostics));
        this.diagnostics = diagnostics != null ? Collections.unmodifiableList(diagnostics) : Collections.emptyList();
    }

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = Collections.emptyList();
    }

    public List<CompilationDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    private static String buildErrorMessage(String baseMessage, List<CompilationDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return baseMessage;
        }
        StringBuilder sb = new StringBuilder(baseMessage).append(":\n");
        for (CompilationDiagnostic d : diagnostics) {
            sb.append("  -> ").append(d.toString()).append("\n");
        }
        return sb.toString();
    }
}
