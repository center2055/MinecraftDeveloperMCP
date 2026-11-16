package io.github.center2055.developermcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** All tools, minus the ones switched off in the config. */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Supplier<Set<String>> disabled;

    public ToolRegistry(Supplier<Set<String>> disabled) {
        this.disabled = disabled;
    }

    public ToolRegistry register(Collection<Tool> list) {
        for (Tool tool : list) {
            tools.put(tool.name(), tool);
        }
        return this;
    }

    public JsonArray describe() {
        JsonArray array = new JsonArray();
        Set<String> off = disabled.get();
        for (Tool tool : tools.values()) {
            if (!off.contains(tool.name())) {
                array.add(tool.describe());
            }
        }
        return array;
    }

    /** Null if there is no such (enabled) tool. */
    public Tool find(String name) {
        Tool tool = tools.get(name);
        return tool == null || disabled.get().contains(name) ? null : tool;
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public static JsonObject emptyArgs() {
        return new JsonObject();
    }
}
