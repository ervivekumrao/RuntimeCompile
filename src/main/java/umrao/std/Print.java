package umrao.std;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Print {

    private static final Logger log = LoggerFactory.getLogger(Print.class);

    public void printMessage() {
        log.info("Print class message.");
    }
}
