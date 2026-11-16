package io.github.center2055.developermcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The result of a tool call: text for the AI, flagged as an error when something went wrong. */
public record ToolResult(String text, boolean isError) {

    public static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text, true);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        content.add(block);
        result.add("content", content);
        if (isError) {
            result.addProperty("isError", true);
        }
        return result;
    }
}
