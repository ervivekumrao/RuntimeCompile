package umrao.compile.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust utility to parse and extract package and class information from Java source code.
 * Strips comments, strings, and text blocks before analysis.
 */
public final class JavaSourceParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*(class|interface|record|enum)\\s+([A-Za-z0-9_$]+)"
    );
    private static final Pattern ANY_TYPE_PATTERN = Pattern.compile(
            "\\b(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*(class|interface|record|enum)\\s+([A-Za-z0-9_$]+)"
    );

    private JavaSourceParser() {
    }

    /**
     * Extracts the fully qualified class name (package + simple name) from Java source code.
     *
     * @param sourceCode raw Java source string
     * @return fully qualified class name
     * @throws IllegalArgumentException if no valid class/interface/record/enum declaration is found
     */
    public static String parseFullClassName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code must not be null or blank.");
        }

        String cleaned = stripCommentsAndLiterals(sourceCode);
        String packageName = extractPackageName(cleaned);
        String simpleName = extractSimpleClassName(cleaned);

        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * Extracts package name from Java source code, or empty string if default package.
     */
    public static String parsePackageName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return "";
        }
        return extractPackageName(stripCommentsAndLiterals(sourceCode));
    }

    /**
     * Extracts the simple class name (without package) from Java source code.
     */
    public static String parseSimpleClassName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code must not be null or blank.");
        }
        return extractSimpleClassName(stripCommentsAndLiterals(sourceCode));
    }

    private static String extractPackageName(String cleanedCode) {
        Matcher matcher = PACKAGE_PATTERN.matcher(cleanedCode);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractSimpleClassName(String cleanedCode) {
        // 1. Prefer public type declaration if present
        Matcher publicMatcher = PUBLIC_TYPE_PATTERN.matcher(cleanedCode);
        if (publicMatcher.find()) {
            return publicMatcher.group(2).trim();
        }

        // 2. Fall back to any top-level type declaration
        Matcher anyMatcher = ANY_TYPE_PATTERN.matcher(cleanedCode);
        if (anyMatcher.find()) {
            return anyMatcher.group(2).trim();
        }

        throw new IllegalArgumentException(
                "Unable to infer class name from source code. Ensure code declares a class, interface, record, or enum."
        );
    }

    /**
     * Strips single-line comments, block comments, string literals, and text blocks
     * so keywords inside strings/comments are not mistaken for class/package declarations.
     */
    public static String stripCommentsAndLiterals(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int len = source.length();
        boolean inBlockComment = false;
        boolean inLineComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean inTextBlock = false;

        for (int i = 0; i < len; i++) {
            char c = source.charAt(i);
            char next = (i + 1 < len) ? source.charAt(i + 1) : '\0';

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                    sb.append(c);
                }
                continue;
            }
            if (inTextBlock) {
                if (c == '"' && next == '"' && i + 2 < len && source.charAt(i + 2) == '"') {
                    inTextBlock = false;
                    i += 2;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped character
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            // Check transitions
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < len && source.charAt(i + 2) == '"') {
                inTextBlock = true;
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }

            sb.append(c);
        }
        return sb.toString();
    }
}
