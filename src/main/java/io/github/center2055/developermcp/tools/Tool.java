package io.github.center2055.developermcp.tools;

import com.google.gson.JsonObject;

/**
 * One MCP tool: its description for the AI client and the code that runs it.
 *
 * @param annotations MCP tool annotations (readOnlyHint, destructiveHint, ...), may be empty
 */
public record Tool(String name, String title, String description, JsonObject inputSchema,
                   JsonObject annotations, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        ToolResult call(JsonObject arguments) throws Exception;
    }

    public JsonObject describe() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("title", title);
        json.addProperty("description", description);
        json.add("inputSchema", inputSchema);
        if (annotations != null && annotations.size() > 0) {
            json.add("annotations", annotations);
        }
        return json;
    }

    public static JsonObject readOnly() {
        JsonObject a = new JsonObject();
        a.addProperty("readOnlyHint", true);
        a.addProperty("openWorldHint", false);
        return a;
    }

    public static JsonObject changesThings(boolean destructive, boolean idempotent) {
        JsonObject a = new JsonObject();
        a.addProperty("readOnlyHint", false);
        a.addProperty("destructiveHint", destructive);
        a.addProperty("idempotentHint", idempotent);
        a.addProperty("openWorldHint", false);
        return a;
    }
}
