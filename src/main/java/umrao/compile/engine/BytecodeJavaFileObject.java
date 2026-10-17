package umrao.compile.engine;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

/**
 * In-memory compiled bytecode container.
 */
public class BytecodeJavaFileObject extends SimpleJavaFileObject {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    public BytecodeJavaFileObject(String className) {
        super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
    }

    @Override
    public OutputStream openOutputStream() {
        return outputStream;
    }

    public byte[] getByteCode() {
        return outputStream.toByteArray();
    }
}
