package umrao.compile.api;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Immutable value object representing a diagnostic message (error, warning, note)
 * produced by the Java compiler.
 */
public record CompilationDiagnostic(
        Diagnostic.Kind kind,
        long lineNumber,
        long columnNumber,
        String code,
        String message
) {
    public static CompilationDiagnostic from(Diagnostic<? extends JavaFileObject> diagnostic) {
        return new CompilationDiagnostic(
                diagnostic.getKind(),
                diagnostic.getLineNumber(),
                diagnostic.getColumnNumber(),
                diagnostic.getCode() != null ? diagnostic.getCode() : "UNKNOWN",
                diagnostic.getMessage(null)
        );
    }

    @Override
    public String toString() {
        return String.format("[%s] Line %d:%d - %s (%s)",
                kind, lineNumber, columnNumber, message, code);
    }
}
