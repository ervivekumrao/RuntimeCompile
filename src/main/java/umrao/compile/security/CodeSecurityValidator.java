package umrao.compile.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates untrusted dynamic Java source code against the configured SecurityPolicy
 * before compiler invocation to prevent RCE, injection, and resource exhaustion attacks.
 */
public class CodeSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(CodeSecurityValidator.class);
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([a-zA-Z0-9_.*]+);", Pattern.MULTILINE);

    private final SecurityPolicy policy;

    public CodeSecurityValidator(SecurityPolicy policy) {
        this.policy = policy != null ? policy : SecurityPolicy.strict();
    }

    public void validate(String sourceCode) {
        if (!policy.isEnforceSandbox()) {
            return;
        }

        if (sourceCode == null || sourceCode.isBlank()) {
            throw new SecurityViolationException("EMPTY_SOURCE", "Source code cannot be null or empty.");
        }

        if (sourceCode.length() > policy.getMaxSourceLengthBytes()) {
            log.warn("Security violation: source code size {} exceeds limit {}", sourceCode.length(), policy.getMaxSourceLengthBytes());
            throw new SecurityViolationException("PAYLOAD_SIZE_LIMIT",
                    String.format("Source code size (%d chars) exceeds maximum permitted limit (%d).",
                            sourceCode.length(), policy.getMaxSourceLengthBytes()));
        }

        // 1. Check blocked imports
        Matcher matcher = IMPORT_PATTERN.matcher(sourceCode);
        while (matcher.find()) {
            String importedPackage = matcher.group(1).trim();
            for (String blocked : policy.getBlockedImports()) {
                if (importedPackage.startsWith(blocked)) {
                    log.warn("Security violation: blocked package import detected: {}", importedPackage);
                    throw new SecurityViolationException("BLOCKED_IMPORT",
                            "Importing restricted package or class: " + importedPackage);
                }
            }
        }

        // 2. Check blocked patterns / forbidden APIs in source code
        for (String pattern : policy.getBlockedPatterns()) {
            if (sourceCode.contains(pattern)) {
                log.warn("Security violation: restricted API token detected: {}", pattern);
                throw new SecurityViolationException("RESTRICTED_API",
                        "Source code contains forbidden API or token: " + pattern);
            }
        }
    }
}
