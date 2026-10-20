package umrao.compile.service.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import umrao.compile.service.util.HttpJsonResponder;

import java.io.IOException;

/**
 * Health check handler returning system liveness, uptime, and version.
 */
public class HealthHandler implements HttpHandler {

    private final long startTimeMs;

    public HealthHandler(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpJsonResponder.sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        long uptime = System.currentTimeMillis() - startTimeMs;
        String json = String.format("{\"status\":\"UP\",\"uptimeMs\":%d,\"version\":\"1.0.0\"}", uptime);
        HttpJsonResponder.sendJson(exchange, 200, json);
    }
}
