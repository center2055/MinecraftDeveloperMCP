package io.github.center2055.developermcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.center2055.developermcp.protocol.McpProtocol;
import io.github.center2055.developermcp.tools.Args;
import io.github.center2055.developermcp.tools.Tool;
import io.github.center2055.developermcp.tools.ToolException;
import io.github.center2055.developermcp.tools.ToolRegistry;
import io.github.center2055.developermcp.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    private final List<String> calls = new ArrayList<>();

    private McpProtocol protocol(Set<String> disabled) {
        ToolRegistry registry = new ToolRegistry(() -> disabled).register(List.of(
                new Tool("echo", "Echo", "Echoes text", Args.Schema.object().string("text", "Text", true).build(),
                        Tool.readOnly(), args -> ToolResult.ok(Args.requireString(args, "text"))),
                new Tool("fail", "Fail", "Always fails", Args.Schema.object().build(), Tool.readOnly(),
                        args -> { throw new ToolException("nope"); }),
                new Tool("crash", "Crash", "Throws", Args.Schema.object().build(), Tool.readOnly(),
                        args -> { throw new IllegalStateException("boom"); })));
        return new McpProtocol(registry, "2.0.0", Logger.getAnonymousLogger(), (client, tool, args) -> calls.add(tool));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void initializeEchoesSupportedVersion() {
        JsonObject r = json(protocol(Set.of()).handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}", "test"));
        JsonObject result = r.getAsJsonObject("result");
        assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
        assertEquals("DeveloperMCP", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertEquals("2.0.0", result.getAsJsonObject("serverInfo").get("version").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertTrue(result.has("instructions"));
    }

    @Test
    void initializeFallsBackToLatestVersion() {
        JsonObject r = json(protocol(Set.of()).handle(
                "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}", "test"));
        assertEquals(McpProtocol.SUPPORTED_VERSIONS.get(0), r.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals("a", r.get("id").getAsString(), "string ids are kept");
    }

    @Test
    void keepsLargeNumericIds() {
        JsonObject r = json(protocol(Set.of()).handle("{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"ping\"}", "test"));
        assertEquals("9007199254740993", r.get("id").getAsBigInteger().toString());
    }

    @Test
    void listsToolsWithoutDisabledOnes() {
        JsonArray tools = json(protocol(Set.of("crash")).handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "test"))
                .getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(2, tools.size());
        JsonObject echo = tools.get(0).getAsJsonObject();
        assertEquals("echo", echo.get("name").getAsString());
        assertEquals("object", echo.getAsJsonObject("inputSchema").get("type").getAsString());
        assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
    }

    @Test
    void callsToolAndReportsToolErrorsAsResults() {
        McpProtocol p = protocol(Set.of());
        JsonObject ok = json(p.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}", "t"));
        assertEquals("hi", ok.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(ok.getAsJsonObject("result").has("isError"));

        JsonObject missing = json(p.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}", "t"));
        assertTrue(missing.getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(missing.toString().contains("Missing required argument 'text'"));

        JsonObject fail = json(p.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"fail\"}}", "t"));
        assertTrue(fail.getAsJsonObject("result").get("isError").getAsBoolean());

        JsonObject crash = json(p.handle("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"crash\"}}", "t"));
        assertTrue(crash.getAsJsonObject("result").get("isError").getAsBoolean());
        assertEquals(List.of("echo", "echo", "fail", "crash"), calls);
    }

    @Test
    void unknownOrDisabledToolIsInvalidParams() {
        JsonObject r = json(protocol(Set.of("echo")).handle(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}}", "t"));
        assertEquals(-32602, r.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void notificationsGetNoAnswer() {
        assertNull(protocol(Set.of()).handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "t"));
    }

    @Test
    void unknownMethodAndParseErrors() {
        McpProtocol p = protocol(Set.of());
        assertEquals(-32601, json(p.handle("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"nope\"}", "t"))
                .getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32700, json(p.handle("{not json", "t")).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void handlesBatches() {
        JsonArray r = JsonParser.parseString(protocol(Set.of()).handle(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]", "t")).getAsJsonArray();
        assertEquals(2, r.size());
    }
}
