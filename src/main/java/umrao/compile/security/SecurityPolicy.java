package umrao.compile.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Encapsulates security constraints for dynamic compilation and execution.
 */
public class SecurityPolicy {

    private final Set<String> blockedImports;
    private final Set<String> blockedPatterns;
    private final int maxSourceLengthBytes;
    private final boolean enforceSandbox;

    public SecurityPolicy(Set<String> blockedImports,
                          Set<String> blockedPatterns,
                          int maxSourceLengthBytes,
                          boolean enforceSandbox) {
        this.blockedImports = Collections.unmodifiableSet(new HashSet<>(blockedImports));
        this.blockedPatterns = Collections.unmodifiableSet(new HashSet<>(blockedPatterns));
        this.maxSourceLengthBytes = maxSourceLengthBytes;
        this.enforceSandbox = enforceSandbox;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Strict enterprise default: blocks execution, reflection, unsafe, native loading, sockets, system exit.
     */
    public static SecurityPolicy strict() {
        return builder()
                .blockImport("java.lang.reflect")
                .blockImport("sun.misc")
                .blockImport("jdk.internal")
                .blockImport("java.net")
                .blockImport("java.nio.file")
                .blockImport("java.io.File")
                .blockPattern("Runtime.getRuntime")
                .blockPattern("ProcessBuilder")
                .blockPattern("System.exit")
                .blockPattern("System.load")
                .blockPattern("System.loadLibrary")
                .blockPattern("Unsafe")
                .maxSourceLength(500_000)
                .enforceSandbox(true)
                .build();
    }

    /**
     * Permissive policy: disables AST blocking for trusted execution environments.
     */
    public static SecurityPolicy permissive() {
        return builder()
                .enforceSandbox(false)
                .maxSourceLength(10_000_000)
                .build();
    }

    public Set<String> getBlockedImports() {
        return blockedImports;
    }

    public Set<String> getBlockedPatterns() {
        return blockedPatterns;
    }

    public int getMaxSourceLengthBytes() {
        return maxSourceLengthBytes;
    }

    public boolean isEnforceSandbox() {
        return enforceSandbox;
    }

    public static class Builder {
        private final Set<String> blockedImports = new HashSet<>();
        private final Set<String> blockedPatterns = new HashSet<>();
        private int maxSourceLengthBytes = 1_000_000;
        private boolean enforceSandbox = true;

        public Builder blockImport(String pkg) {
            this.blockedImports.add(pkg);
            return this;
        }

        public Builder blockPattern(String pattern) {
            this.blockedPatterns.add(pattern);
            return this;
        }

        public Builder maxSourceLength(int bytes) {
            this.maxSourceLengthBytes = bytes;
            return this;
        }

        public Builder enforceSandbox(boolean enforce) {
            this.enforceSandbox = enforce;
            return this;
        }

        public SecurityPolicy build() {
            return new SecurityPolicy(blockedImports, blockedPatterns, maxSourceLengthBytes, enforceSandbox);
        }
    }
}
