package umrao.compile.engine;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Objects;

/**
 * In-memory compiled bytecode container supporting both read and write operations.
 */
public class BytecodeJavaFileObject extends SimpleJavaFileObject {

    private final String className;
    private final ByteArrayOutputStream outputStream;
    private final byte[] byteCode;

    /**
     * Creates an output-oriented file object for compiler to write bytecode into.
     */
    public BytecodeJavaFileObject(String className) {
        super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        this.className = Objects.requireNonNull(className, "className must not be null");
        this.outputStream = new ByteArrayOutputStream();
        this.byteCode = null;
    }

    /**
     * Creates an input-oriented file object initialized with existing bytecode bytes.
     */
    public BytecodeJavaFileObject(String className, byte[] byteCode) {
        super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        this.className = Objects.requireNonNull(className, "className must not be null");
        this.outputStream = null;
        this.byteCode = Objects.requireNonNull(byteCode, "byteCode must not be null");
    }

    public String getClassName() {
        return className;
    }

    @Override
    public OutputStream openOutputStream() {
        if (outputStream == null) {
            throw new UnsupportedOperationException("BytecodeJavaFileObject was created in read-only mode for " + className);
        }
        return outputStream;
    }

    @Override
    public InputStream openInputStream() {
        byte[] bytes = getByteCode();
        if (bytes == null) {
            throw new IllegalStateException("No bytecode available for " + className);
        }
        return new ByteArrayInputStream(bytes);
    }

    public byte[] getByteCode() {
        if (byteCode != null) {
            return byteCode;
        }
        return outputStream != null ? outputStream.toByteArray() : null;
    }
}
