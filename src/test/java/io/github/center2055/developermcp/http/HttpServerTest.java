package io.github.center2055.developermcp.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.center2055.developermcp.protocol.McpProtocol;
import io.github.center2055.developermcp.tools.Args;
import io.github.center2055.developermcp.tools.Tool;
import io.github.center2055.developermcp.tools.ToolRegistry;
import io.github.center2055.developermcp.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServerTest {

    private static final String TOKEN = "test-token-0123456789abcdef";
    private McpHttpServer server;
    private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void start() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ToolRegistry registry = new ToolRegistry(Set::of).register(List.of(
                new Tool("echo", "Echo", "Echoes", Args.Schema.object().string("text", "t", true).build(), Tool.readOnly(),
                        args -> ToolResult.ok(Args.requireString(args, "text")))));
        McpProtocol protocol = new McpProtocol(registry, "test", Logger.getAnonymousLogger(), null);
        server = new McpHttpServer(Logger.getAnonymousLogger(), protocol, new AuthGuard(TOKEN, Logger.getAnonymousLogger()));
        server.start("127.0.0.1", port);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void streamableHttpNeedsTokenAndAnswersJson() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(401, post("/mcp", ping, null).statusCode());
        assertEquals(401, post("/mcp", ping, "Bearer wrong").statusCode());
        HttpResponse<String> ok = post("/mcp", ping, "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(202, post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "Bearer " + TOKEN).statusCode());
        assertEquals(200, post("/api?token=" + TOKEN, ping, null).statusCode(), "query token still works for scripts");
    }

    @Test
    void getOnMcpIs405AndPreflightIsOpen() throws Exception {
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        HttpResponse<String> options = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, options.statusCode());
        assertTrue(options.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Authorization"));
    }

    @Test
    void legacySseTransportWorks() throws Exception {
        HttpRequest sse = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sse?token=" + TOKEN)).GET().build();
        HttpResponse<java.io.InputStream> stream = client.send(sse, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        assertEquals("event: endpoint", reader.readLine());
        String endpoint = reader.readLine().substring("data: ".length());
        assertTrue(endpoint.startsWith("/messages?sessionId="), endpoint);
        assertTrue(!endpoint.contains("token"), "token must not leak into the endpoint URL");
        reader.readLine();

        HttpResponse<String> accepted = post(endpoint, "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"over sse\"}}}", null);
        assertEquals(202, accepted.statusCode(), "the session id authorizes /messages");
        assertEquals("event: message", reader.readLine());
        JsonObject message = JsonParser.parseString(reader.readLine().substring("data: ".length())).getAsJsonObject();
        assertEquals(42, message.get("id").getAsInt());
        assertTrue(message.toString().contains("over sse"));
        stream.body().close();

        assertEquals(401, post("/messages?sessionId=nope", "{}", null).statusCode(), "unknown session needs the token");
        assertEquals(404, post("/messages?sessionId=nope", "{}", "Bearer " + TOKEN).statusCode());
    }

    @Test
    void locksOutAfterTooManyWrongTokens() {
        AtomicLong now = new AtomicLong(1_000_000);
        AuthGuard guard = new AuthGuard(TOKEN, Logger.getAnonymousLogger(), now::get);
        for (int i = 0; i < AuthGuard.MAX_FAILURES - 1; i++) {
            assertEquals(AuthGuard.Result.DENIED, guard.check("1.2.3.4", "wrong"));
        }
        assertEquals(AuthGuard.Result.BLOCKED, guard.check("1.2.3.4", "wrong"));
        assertEquals(AuthGuard.Result.BLOCKED, guard.check("1.2.3.4", TOKEN), "no guessing while blocked");
        assertEquals(AuthGuard.Result.OK, guard.check("5.6.7.8", null, TOKEN), "other addresses are unaffected");
        now.addAndGet(AuthGuard.BLOCK_MILLIS + 1);
        assertEquals(AuthGuard.Result.OK, guard.check("1.2.3.4", TOKEN));
    }
}
