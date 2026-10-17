package umrao.compile.security;

/**
 * Thrown when dynamically submitted source code violates the active security policy.
 */
public class SecurityViolationException extends RuntimeException {

    private final String violationRule;

    public SecurityViolationException(String violationRule, String message) {
        super("Security Policy Violation [" + violationRule + "]: " + message);
        this.violationRule = violationRule;
    }

    public String getViolationRule() {
        return violationRule;
    }
}
