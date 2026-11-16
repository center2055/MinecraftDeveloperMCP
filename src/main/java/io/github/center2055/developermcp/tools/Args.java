package io.github.center2055.developermcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Reading tool arguments and building JSON schemas for them. */
public final class Args {

    private Args() {
    }

    public static String requireString(JsonObject args, String name) throws ToolException {
        String value = optString(args, name, null);
        if (value == null) {
            throw new ToolException("Missing required argument '" + name + "'.");
        }
        return value;
    }

    public static String optString(JsonObject args, String name, String fallback) throws ToolException {
        JsonElement e = args.get(name);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        if (!e.isJsonPrimitive()) {
            throw new ToolException("Argument '" + name + "' must be a string.");
        }
        return e.getAsString();
    }

    public static int optInt(JsonObject args, String name, int fallback, int min, int max) throws ToolException {
        JsonElement e = args.get(name);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            long value = p.isNumber() ? p.getAsLong() : Long.parseLong(p.getAsString().trim());
            return (int) Math.max(min, Math.min(max, value));
        } catch (RuntimeException ex) {
            throw new ToolException("Argument '" + name + "' must be a whole number.");
        }
    }

    public static boolean optBool(JsonObject args, String name, boolean fallback) throws ToolException {
        JsonElement e = args.get(name);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString().trim();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        throw new ToolException("Argument '" + name + "' must be true or false.");
    }

    /** Small builder for the JSON schema of a tool's arguments. */
    public static final class Schema {
        private final JsonObject properties = new JsonObject();
        private final JsonArray required = new JsonArray();

        public static Schema object() {
            return new Schema();
        }

        public Schema string(String name, String description, boolean isRequired) {
            return property(name, "string", description, isRequired);
        }

        public Schema integer(String name, String description, boolean isRequired) {
            return property(name, "integer", description, isRequired);
        }

        public Schema bool(String name, String description, boolean isRequired) {
            return property(name, "boolean", description, isRequired);
        }

        private Schema property(String name, String type, String description, boolean isRequired) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", description);
            properties.add(name, p);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public JsonObject build() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties);
            if (required.size() > 0) {
                schema.add("required", required);
            }
            schema.addProperty("additionalProperties", false);
            return schema;
        }
    }
}
