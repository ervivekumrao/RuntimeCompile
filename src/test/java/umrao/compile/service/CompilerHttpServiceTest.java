package umrao.compile.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.service.util.HttpJsonResponder;
import umrao.compile.store.BytecodeStore;
import umrao.compile.store.DatabaseBytecodeStore;
import umrao.compile.store.FileSystemBytecodeStore;
import umrao.compile.store.RedisBytecodeStore;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerHttpServiceTest {

    private CompilerHttpService service;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        Path tempDir = Files.createTempDirectory("compiler-service-test-");
        BytecodeStore redisStore = RedisBytecodeStore.inMemory();
        BytecodeStore fsStore = new FileSystemBytecodeStore(tempDir);
        BytecodeStore dbStore = DatabaseBytecodeStore.h2InMemory("test_service_db");

        DynamicCompiler compiler = InMemoryCompiler.builder()
                .withRedisStore(redisStore)
                .withFileSystemStore(fsStore)
                .withDatabaseStore(dbStore)
                .build();

        // 0 lets the OS pick an available ephemeral port
        service = new CompilerHttpService(0, compiler, compiler.getTieredRegistry());
        service.start();
        port = service.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    @DisplayName("GET /api/health returns HTTP 200 with UP status")
    void shouldReturnHealthStatus() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("GET /api/tiers returns status of all 4 storage tiers")
    void shouldReturnTierStatus() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/tiers"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("tier1_classloader", "tier2_redis", "tier3_filesystem", "tier4_database");
    }

    @Test
    @DisplayName("POST /api/compile compiles standalone Java code and executes via POST /api/execute")
    void shouldCompileAndExecuteDynamicClass() throws Exception {
        String source = """
                package com.test.service;

                public class MathWorker {
                    public int calculateSum(int a, int b) {
                        return a + b;
                    }

                    public String formatGreeting(String name) {
                        return "Welcome, " + name + "!";
                    }
                }
                """;

        // 1. Compile via raw text
        HttpRequest compileReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(source))
                .build();

        HttpResponse<String> compileResp = client.send(compileReq, HttpResponse.BodyHandlers.ofString());
        assertThat(compileResp.statusCode()).isEqualTo(200);
        assertThat(compileResp.body()).contains("\"success\":true", "com.test.service.MathWorker");

        // 2. Execute method calculateSum(25, 75)
        String execPayload = """
                {
                    "className": "com.test.service.MathWorker",
                    "methodName": "calculateSum",
                    "args": ["25", "75"]
                }
                """;
        HttpRequest execReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(execPayload))
                .build();

        HttpResponse<String> execResp = client.send(execReq, HttpResponse.BodyHandlers.ofString());
        assertThat(execResp.statusCode()).isEqualTo(200);
        assertThat(execResp.body()).contains("\"result\":\"100\"");

        // 3. Execute method formatGreeting("Antigravity")
        String greetPayload = """
                {
                    "className": "com.test.service.MathWorker",
                    "methodName": "formatGreeting",
                    "args": ["Antigravity"]
                }
                """;
        HttpRequest greetReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(greetPayload))
                .build();

        HttpResponse<String> greetResp = client.send(greetReq, HttpResponse.BodyHandlers.ofString());
        assertThat(greetResp.statusCode()).isEqualTo(200);
        assertThat(greetResp.body()).contains("\"result\":\"Welcome, Antigravity!\"");
    }

    @Test
    @DisplayName("POST /api/compile accepts JSON with sourceCode field and handles compile errors")
    void shouldHandleJsonCompilationAndErrors() throws Exception {
        // Successful JSON compile
        String jsonPayload = "{\"sourceCode\": \"package com.test.json; public class JsonTest { public String msg() { return \\\"ok\\\"; } }\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"className\":\"com.test.json.JsonTest\"");

        // Error JSON compile
        String errPayload = "{\"sourceCode\": \"package com.test.bad; public class Broken { int a = ; }\"}";
        HttpRequest errReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(errPayload))
                .build();

        HttpResponse<String> errResp = client.send(errReq, HttpResponse.BodyHandlers.ofString());
        assertThat(errResp.statusCode()).isEqualTo(422);
        assertThat(errResp.body()).contains("\"success\":false", "diagnostics");
    }

    @Test
    @DisplayName("POST /api/compile with compiler=JANINO compiles fast and executes via /api/execute")
    void shouldCompileWithJaninoAndExecute() throws Exception {
        String payload = """
                {
                    "compiler": "JANINO",
                    "sourceCode": "package com.test.janino; public class JaninoCalculator { public int multiply(int a, int b) { return a * b; } }"
                }
                """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"success\":true", "\"compiler\":\"JANINO\"", "com.test.janino.JaninoCalculator");

        // Execute via /api/execute
        String execPayload = """
                {
                    "className": "com.test.janino.JaninoCalculator",
                    "methodName": "multiply",
                    "args": ["6", "7"]
                }
                """;
        HttpRequest execReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(execPayload))
                .build();

        HttpResponse<String> execResp = client.send(execReq, HttpResponse.BodyHandlers.ofString());
        assertThat(execResp.statusCode()).isEqualTo(200);
        assertThat(execResp.body()).contains("\"result\":\"42\"");
    }

    @Test
    @DisplayName("POST /api/compile with compiler=GROOVY compiles and executes via /api/execute")
    void shouldCompileWithGroovyAndExecute() throws Exception {
        String payload = """
                {
                    "compiler": "GROOVY",
                    "sourceCode": "package com.test.groovy; class GroovyGreeter { String greet(String name) { return 'Hello ' + name } }"
                }
                """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"success\":true", "\"compiler\":\"GROOVY\"", "com.test.groovy.GroovyGreeter");

        // Execute via /api/execute
        String execPayload = """
                {
                    "className": "com.test.groovy.GroovyGreeter",
                    "methodName": "greet",
                    "args": ["World"]
                }
                """;
        HttpRequest execReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(execPayload))
                .build();

        HttpResponse<String> execResp = client.send(execReq, HttpResponse.BodyHandlers.ofString());
        assertThat(execResp.statusCode()).isEqualTo(200);
        assertThat(execResp.body()).contains("\"result\":\"Hello World\"");
    }

    @Test
    @DisplayName("POST /api/compile with query param ?compiler=janino compiles using Janino")
    void shouldCompileViaQueryParam() throws Exception {
        String code = "package com.test.query; public class QueryParamClass { public int getVal() { return 99; } }";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile?compiler=janino"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(code))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"success\":true", "\"compiler\":\"JANINO\"", "com.test.query.QueryParamClass");
    }

    @Test
    @DisplayName("POST /api/compile with header X-Compiler-Engine: GROOVY compiles using Groovy")
    void shouldCompileViaHeader() throws Exception {
        String code = "package com.test.hdr; class HeaderClass { int answer() { 42 } }";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("X-Compiler-Engine", "GROOVY")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(code))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"success\":true", "\"compiler\":\"GROOVY\"", "com.test.hdr.HeaderClass");
    }

    @Test
    @DisplayName("POST /api/compile with unsupported compiler returns 400 Bad Request")
    void shouldReturnBadRequestForUnsupportedCompiler() throws Exception {
        String payload = """
                {
                    "compiler": "UNSUPPORTED_ENGINE",
                    "sourceCode": "package com.test; public class Test {}"
                }
                """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("\"success\":false", "Unsupported compiler");
    }

    @Test
    @DisplayName("Client can load compiled class into custom ClassLoader from POST /api/compile Base64 response")
    void shouldAllowClientToLoadClassFromBase64Response() throws Exception {
        String code = "package com.client.remote; public class ClientMath { public int add(int a, int b) { return a + b; } }";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sourceCode\":\"" + code + "\"}"))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        String b64 = HttpJsonResponder.extractJsonStringField(resp.body(), "bytecode");
        assertThat(b64).isNotBlank();

        byte[] bytecode = Base64.getDecoder().decode(b64);
        class ClientClassLoader extends ClassLoader {
            public ClientClassLoader(ClassLoader parent) { super(parent); }
            public Class<?> define(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        ClientClassLoader clientLoader = new ClientClassLoader(getClass().getClassLoader());
        Class<?> clazz = clientLoader.define("com.client.remote.ClientMath", bytecode);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Object sum = clazz.getMethod("add", int.class, int.class).invoke(inst, 20, 30);
        assertThat(sum).isEqualTo(50);
    }

    @Test
    @DisplayName("Client can load class binary from GET /api/bytecode/{className}")
    void shouldAllowClientToFetchRawBytecodeAndLoad() throws Exception {
        // 1. Compile
        String code = "package com.client.raw; public class RawClass { public String info() { return \"Loaded Bytecode\"; } }";
        HttpRequest compileReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(code))
                .build();
        client.send(compileReq, HttpResponse.BodyHandlers.ofString());

        // 2. Fetch binary bytecode
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/bytecode/com.client.raw.RawClass"))
                .GET()
                .build();
        HttpResponse<byte[]> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(getResp.statusCode()).isEqualTo(200);
        assertThat(getResp.headers().firstValue("Content-Type")).contains("application/java-vm-type");
        byte[] bytes = getResp.body();
        assertThat(bytes).isNotEmpty();

        // 3. Client loads and invokes
        class ClientByteLoader extends ClassLoader {
            public ClientByteLoader(ClassLoader parent) { super(parent); }
            public Class<?> define(String name, byte[] b) {
                return defineClass(name, b, 0, b.length);
            }
        }
        ClientByteLoader loader = new ClientByteLoader(getClass().getClassLoader());
        Class<?> loadedClass = loader.define("com.client.raw.RawClass", bytes);
        Object obj = loadedClass.getDeclaredConstructor().newInstance();
        Object info = loadedClass.getMethod("info").invoke(obj);
        assertThat(info).isEqualTo("Loaded Bytecode");
    }

    @Test
    @DisplayName("Client can load class directly using standard Java URLClassLoader from /api/classes/")
    void shouldAllowClientUrlClassLoaderLoading() throws Exception {
        // 1. Compile
        String code = "package com.client.url; public class RemoteUrlClass { public int score() { return 100; } }";
        HttpRequest compileReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/compile"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(code))
                .build();
        client.send(compileReq, HttpResponse.BodyHandlers.ofString());

        // 2. Standard Java URLClassLoader
        URL serviceClassesUrl = URI.create("http://localhost:" + port + "/api/classes/").toURL();
        try (URLClassLoader urlLoader = new URLClassLoader(new URL[]{serviceClassesUrl}, getClass().getClassLoader())) {
            Class<?> remoteClass = urlLoader.loadClass("com.client.url.RemoteUrlClass");
            assertThat(remoteClass).isNotNull();
            Object instance = remoteClass.getDeclaredConstructor().newInstance();
            Object score = remoteClass.getMethod("score").invoke(instance);
            assertThat(score).isEqualTo(100);
        }
    }
}
