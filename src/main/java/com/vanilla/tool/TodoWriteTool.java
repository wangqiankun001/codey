package com.vanilla.tool;

import java.util.ArrayList;
import java.util.List;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * todo_write：维护一份任务清单，让模型可以拆解多步任务并跟踪进度。
 */
public class TodoWriteTool implements Tool {

    private static final String TOOL_NAME = "todo_write";

    private static List<Todo> todos = new ArrayList<>();

    public static List<Todo> getTodos() {
        return todos;
    }

    @Override
    public ToolSpecification getSpecification() {
        // items：单条任务
        JsonObjectSchema todoItemSchema = JsonObjectSchema.builder()
                .description("A single task in the list.")
                .addStringProperty("content",
                        "Short, human-readable description of what needs to be done.")
                .addEnumProperty("status",
                        List.of("pending", "in_progress", "completed"),
                        "Current state of the task: pending (not started), "
                                + "in_progress (currently working on it), completed (done).")
                .required("content", "status")
                .build();

        // todos：任务数组，整体替换式更新
        JsonArraySchema todosSchema = JsonArraySchema.builder()
                .description("The full task list. Replace the entire list on every call.")
                .items(todoItemSchema)
                .build();

        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("Create and manage a task list. Each call replaces the previous list "
                        + "in its entirety. Use it to break down multi-step work and track progress.")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("todos", todosSchema)
                        .required("todos")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        String todosStr = JSONUtil.parseObj(request.arguments()).getStr("todos");
        List<Todo> todos = JSONUtil.toList(todosStr, Todo.class);
        TodoWriteTool.todos = todos;
        return String.format("Updated %s tasks", todos.size());
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Todo {
        String content;
        String status;
    }
}
