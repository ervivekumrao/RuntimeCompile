package umrao.compile.service.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import umrao.compile.service.util.HttpJsonResponder;
import umrao.compile.store.TieredBytecodeRegistry;

import java.io.IOException;

/**
 * Endpoint reporting status of all 4 storage tiers:
 * ClassLoader (RAM) &gt; Redis (Cache) &gt; Filesystem (Volume) &gt; DB (External SQL Database).
 */
public class TiersStatusHandler implements HttpHandler {

    private final TieredBytecodeRegistry registry;

    public TiersStatusHandler(TieredBytecodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpJsonResponder.sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        int ramCount = registry != null ? registry.getInMemoryRepository().size() : 0;
        String redisType = registry != null && registry.getRedisStore() != null
                ? registry.getRedisStore().getStorageType() : "DISABLED";
        String fsType = registry != null && registry.getFileSystemStore() != null
                ? registry.getFileSystemStore().getStorageType() : "DISABLED";
        String dbType = registry != null && registry.getDatabaseStore() != null
                ? registry.getDatabaseStore().getStorageType() : "DISABLED";

        String json = String.format(
                "{\"tier1_classloader\":{\"status\":\"ACTIVE\",\"inMemoryCachedClasses\":%d}," +
                "\"tier2_redis\":{\"status\":\"ACTIVE\",\"type\":\"%s\"}," +
                "\"tier3_filesystem\":{\"status\":\"ACTIVE\",\"type\":\"%s\"}," +
                "\"tier4_database\":{\"status\":\"ACTIVE\",\"type\":\"%s\"}}",
                ramCount, redisType, fsType, dbType
        );
        HttpJsonResponder.sendJson(exchange, 200, json);
    }
}
