package umrao.compile.service.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.api.CompilationDiagnostic;
import umrao.compile.api.CompilationException;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.JavaSourceParser;
import umrao.compile.jit.GroovyJitCompiler;
import umrao.compile.jit.JaninoJitCompiler;
import umrao.compile.service.util.HttpJsonResponder;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint handler for compiling arbitrary standalone Java or Groovy source code
 * with pluggable compiler engine selection (JAVAC, JANINO, GROOVY),
 * returning the compiled bytecode and automatically persisting it across active storage tiers.
 */
public class CompileHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(CompileHandler.class);

    private final DynamicCompiler compiler;
    private final JaninoJitCompiler janinoCompiler;
    private final GroovyJitCompiler groovyCompiler;

    public CompileHandler(DynamicCompiler compiler) {
        this(compiler, new JaninoJitCompiler(), new GroovyJitCompiler());
    }

    public CompileHandler(DynamicCompiler compiler, JaninoJitCompiler janinoCompiler, GroovyJitCompiler groovyCompiler) {
        this.compiler = compiler;
        this.janinoCompiler = janinoCompiler != null ? janinoCompiler : new JaninoJitCompiler();
        this.groovyCompiler = groovyCompiler != null ? groovyCompiler : new GroovyJitCompiler();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpJsonResponder.sendError(exchange, 405, "Method Not Allowed. Use POST.");
            return;
        }

        String body = HttpJsonResponder.readBody(exchange);
        if (body == null || body.isBlank()) {
            HttpJsonResponder.sendError(exchange, 400, "Missing request body. Provide Java source code.");
            return;
        }

        String sourceCode;
        String requestedCompiler = null;

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.contains("application/json")) {
            sourceCode = HttpJsonResponder.extractJsonStringField(body, "sourceCode");
            if (sourceCode == null || sourceCode.isBlank()) {
                HttpJsonResponder.sendError(exchange, 400, "JSON must contain 'sourceCode' field with Java source code string");
                return;
            }
            requestedCompiler = HttpJsonResponder.extractJsonStringField(body, "compiler");
            if (requestedCompiler == null) {
                requestedCompiler = HttpJsonResponder.extractJsonStringField(body, "engine");
            }
        } else {
            sourceCode = body;
        }

        // 1. Query parameter override (?compiler=... or ?engine=...)
        if (requestedCompiler == null || requestedCompiler.isBlank()) {
            requestedCompiler = extractQueryParam(exchange, "compiler");
            if (requestedCompiler == null) {
                requestedCompiler = extractQueryParam(exchange, "engine");
            }
        }

        // 2. HTTP header override (X-Compiler-Engine or X-Compiler)
        if (requestedCompiler == null || requestedCompiler.isBlank()) {
            requestedCompiler = exchange.getRequestHeaders().getFirst("X-Compiler-Engine");
            if (requestedCompiler == null) {
                requestedCompiler = exchange.getRequestHeaders().getFirst("X-Compiler");
            }
        }

        // Default compiler is standard JAVAC in-memory
        if (requestedCompiler == null || requestedCompiler.isBlank()) {
            requestedCompiler = "JAVAC";
        }

        String compilerType = requestedCompiler.trim().toUpperCase();
        long start = System.nanoTime();

        switch (compilerType) {
            case "JAVAC", "IN_MEMORY", "JAVAC_IN_MEMORY" -> compileWithJavac(exchange, sourceCode, start);
            case "JANINO" -> compileWithJanino(exchange, sourceCode, start);
            case "GROOVY", "GROOVY_COMPILE_STATIC" -> compileWithGroovy(exchange, sourceCode, start);
            default -> HttpJsonResponder.sendError(exchange, 400, String.format(
                    "Unsupported compiler '%s'. Supported compilers: JAVAC, JANINO, GROOVY",
                    requestedCompiler
            ));
        }
    }

    private void compileWithJavac(HttpExchange exchange, String sourceCode, long startNano) throws IOException {
        try {
            CompilationResult<Object> result = compiler.compile(sourceCode);
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

            if (result.isSuccess()) {
                String className = result.getCompiledClass() != null ? result.getCompiledClass().getName() : "unknown";
                byte[] bytecode = compiler.getCompiledBytecode(className);
                String b64Bytecode = bytecode != null ? Base64.getEncoder().encodeToString(bytecode) : "";

                String json = String.format(
                        "{\"success\":true,\"compiler\":\"JAVAC\",\"className\":\"%s\",\"compileTimeMs\":%d,\"cached\":%b,\"bytecode\":\"%s\",\"diagnostics\":[]}",
                        HttpJsonResponder.escapeJson(className), elapsedMs, result.isCached(), b64Bytecode
                );
                HttpJsonResponder.sendJson(exchange, 200, json);
            } else {
                sendDiagnosticFailure(exchange, elapsedMs, "Compilation failed", result.getDiagnostics());
            }
        } catch (CompilationException ce) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            sendDiagnosticFailure(exchange, elapsedMs, ce.getMessage(), ce.getDiagnostics());
        } catch (Exception e) {
            log.error("Javac compilation error: {}", e.getMessage(), e);
            HttpJsonResponder.sendError(exchange, 500, "Compilation exception: " + e.getMessage());
        }
    }

    private void compileWithJanino(HttpExchange exchange, String sourceCode, long startNano) throws IOException {
        try {
            String className = JavaSourceParser.parseFullClassName(sourceCode);
            Map<String, byte[]> bytecodes = janinoCompiler.compileToBytecodes(className, sourceCode);
            for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
                compiler.registerCompiledClass(entry.getKey(), entry.getValue());
            }
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

            byte[] bytecode = bytecodes.get(className);
            if (bytecode == null && !bytecodes.isEmpty()) {
                bytecode = bytecodes.values().iterator().next();
            }
            String b64Bytecode = bytecode != null ? Base64.getEncoder().encodeToString(bytecode) : "";

            String json = String.format(
                    "{\"success\":true,\"compiler\":\"JANINO\",\"className\":\"%s\",\"compileTimeMs\":%d,\"cached\":false,\"bytecode\":\"%s\",\"diagnostics\":[]}",
                    HttpJsonResponder.escapeJson(className), elapsedMs, b64Bytecode
            );
            HttpJsonResponder.sendJson(exchange, 200, json);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            List<CompilationDiagnostic> diags = Collections.emptyList();
            if (cause instanceof CompileException ce) {
                Location loc = ce.getLocation();
                long line = loc != null ? loc.getLineNumber() : -1;
                long col = loc != null ? loc.getColumnNumber() : -1;
                diags = List.of(new CompilationDiagnostic(Diagnostic.Kind.ERROR, line, col, "JANINO_ERROR", ce.getMessage()));
            }
            sendDiagnosticFailure(exchange, elapsedMs, "Janino compilation error: " + cause.getMessage(), diags);
        }
    }

    private void compileWithGroovy(HttpExchange exchange, String sourceCode, long startNano) throws IOException {
        try {
            String className;
            try {
                className = JavaSourceParser.parseFullClassName(sourceCode);
            } catch (Exception e) {
                className = "DynamicGroovyScript";
            }
            Map<String, byte[]> bytecodes = groovyCompiler.compileToBytecodes(className, sourceCode);
            for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
                compiler.registerCompiledClass(entry.getKey(), entry.getValue());
            }
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

            byte[] bytecode = bytecodes.get(className);
            if (bytecode == null && !bytecodes.isEmpty()) {
                bytecode = bytecodes.values().iterator().next();
            }
            String b64Bytecode = bytecode != null ? Base64.getEncoder().encodeToString(bytecode) : "";

            String json = String.format(
                    "{\"success\":true,\"compiler\":\"GROOVY\",\"className\":\"%s\",\"compileTimeMs\":%d,\"cached\":false,\"bytecode\":\"%s\",\"diagnostics\":[]}",
                    HttpJsonResponder.escapeJson(className), elapsedMs, b64Bytecode
            );
            HttpJsonResponder.sendJson(exchange, 200, json);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            List<CompilationDiagnostic> diags = List.of(
                    new CompilationDiagnostic(Diagnostic.Kind.ERROR, -1, -1, "GROOVY_ERROR", cause.getMessage())
            );
            sendDiagnosticFailure(exchange, elapsedMs, "Groovy compilation error: " + cause.getMessage(), diags);
        }
    }

    private static String extractQueryParam(HttpExchange exchange, String paramName) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase(paramName)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendDiagnosticFailure(HttpExchange exchange, long elapsedMs, String message, List<CompilationDiagnostic> diags) throws IOException {
        StringBuilder diagJson = new StringBuilder("[");
        if (diags != null) {
            for (int i = 0; i < diags.size(); i++) {
                CompilationDiagnostic d = diags.get(i);
                diagJson.append(String.format(
                        "{\"kind\":\"%s\",\"line\":%d,\"column\":%d,\"message\":\"%s\"}",
                        d.kind(), d.lineNumber(), d.columnNumber(), HttpJsonResponder.escapeJson(d.message())
                ));
                if (i < diags.size() - 1) diagJson.append(",");
            }
        }
        diagJson.append("]");

        String json = String.format(
                "{\"success\":false,\"compileTimeMs\":%d,\"error\":\"%s\",\"diagnostics\":%s}",
                elapsedMs, HttpJsonResponder.escapeJson(message), diagJson
        );
        HttpJsonResponder.sendJson(exchange, 422, json);
    }
}
