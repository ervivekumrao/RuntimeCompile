package umrao.compile.engine;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/**
 * In-memory Java source file representation.
 */
public class SourceJavaFileObject extends SimpleJavaFileObject {

    private final String sourceCode;

    public SourceJavaFileObject(String className, String sourceCode) {
        super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.sourceCode = sourceCode;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return sourceCode;
    }
}
