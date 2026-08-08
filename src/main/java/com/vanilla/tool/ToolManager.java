package com.vanilla.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vanilla.tool.task.ClaimTaskTool;
import com.vanilla.tool.task.CompleteTaskTool;
import com.vanilla.tool.task.CreateTaskTool;
import com.vanilla.tool.task.ListTasksTool;

import dev.langchain4j.agent.tool.ToolSpecification;

public class ToolManager {

    private static Map<String, ToolSpecification> TOOL_SPECIFICATIONS = new HashMap<>();

    private static Map<String, Tool> HANDLERS = new HashMap<>();

    static {
        register(new BashTool());
        register(new ReadFileTool());
        register(new WriteFileTool());
        register(new EditFileTool());
        register(new GlobTool());
        register(new TodoWriteTool());
        register(new SpawnSubagentTool());
        register(new LoadSkillTool());
        register(new CreateTaskTool());
        register(new ListTasksTool());
        register(new GetTaskTool());
        register(new ClaimTaskTool());
        register(new CompleteTaskTool());
    }

    public static void register(Tool tool) {
        TOOL_SPECIFICATIONS.put(tool.getSpecification().name(), tool.getSpecification());
        HANDLERS.put(tool.getSpecification().name(), tool);
    }

    public static Tool handler(String toolName) {
        return HANDLERS.get(toolName);
    }

    public static List<ToolSpecification> toolSpecifications() {
        return TOOL_SPECIFICATIONS.values().stream().toList();
    }

    public static List<ToolSpecification> subagentToolSpecifications() {
        return TOOL_SPECIFICATIONS.values().stream().filter(tool -> !"task".equals(tool.name())).toList();
    }

    public static List<String> enabledTools() {
        return List.of(HANDLERS.keySet().toArray(String[]::new));
    }
}
