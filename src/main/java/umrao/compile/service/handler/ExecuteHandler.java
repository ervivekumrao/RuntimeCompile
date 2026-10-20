package umrao.compile.service.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.service.util.HttpJsonResponder;
import umrao.compile.store.TieredBytecodeRegistry;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

/**
 * REST endpoint handler for dynamically resolving and executing methods
 * on compiled classes stored across the tiered storage hierarchy.
 */
public class ExecuteHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecuteHandler.class);

    private final DynamicCompiler compiler;
    private final TieredBytecodeRegistry registry;

    public ExecuteHandler(DynamicCompiler compiler, TieredBytecodeRegistry registry) {
        this.compiler = compiler;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpJsonResponder.sendError(exchange, 405, "Method Not Allowed. Use POST.");
            return;
        }

        String body = HttpJsonResponder.readBody(exchange);
        if (body == null || body.isBlank()) {
            HttpJsonResponder.sendError(exchange, 400, "Missing request body");
            return;
        }

        String className = HttpJsonResponder.extractJsonStringField(body, "className");
        String methodName = HttpJsonResponder.extractJsonStringField(body, "methodName");
        List<String> argsList = HttpJsonResponder.extractJsonStringArrayField(body, "args");
        if (argsList.isEmpty()) {
            argsList = HttpJsonResponder.extractJsonStringArrayField(body, "arguments");
        }

        if (className == null || className.isBlank()) {
            HttpJsonResponder.sendError(exchange, 400, "Missing 'className' field");
            return;
        }
        if (methodName == null || methodName.isBlank()) {
            HttpJsonResponder.sendError(exchange, 400, "Missing 'methodName' field");
            return;
        }

        try {
            Class<?> clazz;
            try {
                clazz = compiler.loadClass(className);
            } catch (ClassNotFoundException cnfe) {
                Optional<byte[]> bytecode = registry != null ? registry.findBytecode(className) : Optional.empty();
                if (bytecode.isPresent()) {
                    compiler.registerCompiledClass(className, bytecode.get());
                    clazz = compiler.loadClass(className);
                } else {
                    HttpJsonResponder.sendError(exchange, 404, "Class not found: " + className);
                    return;
                }
            }

            Method targetMethod = null;
            Object[] invocationArgs = null;

            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName)) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (paramTypes.length == argsList.size()) {
                        try {
                            Object[] parsed = parseArgs(paramTypes, argsList);
                            targetMethod = m;
                            invocationArgs = parsed;
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            if (targetMethod == null) {
                HttpJsonResponder.sendError(exchange, 404, String.format(
                        "Method '%s' with %d arguments not found on class '%s'",
                        methodName, argsList.size(), className
                ));
                return;
            }

            Object targetInstance = null;
            if (!Modifier.isStatic(targetMethod.getModifiers())) {
                targetInstance = clazz.getDeclaredConstructor().newInstance();
            }

            Object output = targetMethod.invoke(targetInstance, invocationArgs);
            String resultStr = output != null ? String.valueOf(output) : "null";

            String json = String.format(
                    "{\"success\":true,\"className\":\"%s\",\"methodName\":\"%s\",\"result\":\"%s\"}",
                    HttpJsonResponder.escapeJson(className),
                    HttpJsonResponder.escapeJson(methodName),
                    HttpJsonResponder.escapeJson(resultStr)
            );
            HttpJsonResponder.sendJson(exchange, 200, json);
        } catch (Exception e) {
            log.error("Execution error: {}", e.getMessage(), e);
            HttpJsonResponder.sendError(exchange, 500, "Execution error: " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private static Object[] parseArgs(Class<?>[] paramTypes, List<String> strArgs) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            String raw = strArgs.get(i);
            if (pt == String.class) {
                args[i] = raw;
            } else if (pt == int.class || pt == Integer.class) {
                args[i] = Integer.parseInt(raw);
            } else if (pt == long.class || pt == Long.class) {
                args[i] = Long.parseLong(raw);
            } else if (pt == double.class || pt == Double.class) {
                args[i] = Double.parseDouble(raw);
            } else if (pt == float.class || pt == Float.class) {
                args[i] = Float.parseFloat(raw);
            } else if (pt == boolean.class || pt == Boolean.class) {
                args[i] = Boolean.parseBoolean(raw);
            } else {
                args[i] = raw;
            }
        }
        return args;
    }
}
