package com.vanilla.prompt;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.vanilla.memory.MemoryManager;
import com.vanilla.tool.ToolManager;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

public class SystemMessageBuilder {

    public static final String WORKSPACE = System.getProperty("user.dir");

    private static String lastContextKey;

    private static String lastPrompt;

    private static Map<String, String> PROMPT_SECTIONS = Map.of(
            "identity", "You are a coding agent. Act, don't explain.",
            "tools", "Available tools: bash, read_file, write_file.",
            "workspace", String.format("Working directory: %s", WORKSPACE),
            "memory", "Relevant memories are injected below when available.");

    public static String getSystemPrompt() {
        return getSystemPrompt(updateContext());
    }

    public static String getSystemPrompt(Context context) {
        String key = JSONUtil.toJsonStr(context);
        if (StrUtil.equals(lastContextKey, key)){

            return lastPrompt;
        }
        lastPrompt = assembleSystemPrompt(context);
        return lastPrompt;
    }

    public static String assembleSystemPrompt(Context context) {
        StringJoiner joiner = new StringJoiner("\n\n");
        joiner.add(PROMPT_SECTIONS.get("identity"));
        joiner.add(PROMPT_SECTIONS.get("tools"));
        joiner.add(PROMPT_SECTIONS.get("workspace"));
        if (context.memories() != null) {
            joiner.add(PROMPT_SECTIONS.get("memory"));
            joiner.add("Relevant memories:\n" + context.memories());
        }
        return joiner.toString();
    }

    public static Context updateContext() {
        return new Context(ToolManager.enabledTools(), WORKSPACE, MemoryManager.readMemoryIndex());
    }

    /**
     * Context
     */
    public static record Context(
            List<String> enableTools,
            String workspace,
            String memories) {
    }
}
