package umrao.std;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standard enterprise logging utility wrapping the SLF4J facade.
 * Provides backwards-compatible printLog() while exposing modern structured logging.
 */
public class Log {

    private static final Logger log = LoggerFactory.getLogger(Log.class);

    public void printLog() {
        log.info("Log class message.");
    }

    public Logger getLogger() {
        return log;
    }
}
