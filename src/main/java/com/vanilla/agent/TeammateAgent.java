package com.vanilla.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import com.vanilla.inbox.AgentMsgType;
import com.vanilla.inbox.MessageBus;
import com.vanilla.tool.BashTool;
import com.vanilla.tool.EditFileTool;
import com.vanilla.tool.ReadFileTool;
import com.vanilla.tool.SendMessageToTeammateTool;
import com.vanilla.tool.Tool;
import com.vanilla.tool.ToolManager;
import com.vanilla.tool.WriteFileTool;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class TeammateAgent extends Thread {

    private String name;
    private String role;
    private String prompt;
    private String system;
    private OpenAiChatModel client;
    private List<ChatMessage> history = new ArrayList<>();
    private List<Tool> tools = new ArrayList<>();
    private List<ToolSpecification> toolSpecifications = new ArrayList<>();

    public TeammateAgent(String name, String role, String prompt, OpenAiChatModel client,
            Tool... additionalTools) {
        super(name + "-" + role + "-teammate");
        this.name = name;
        this.role = role;
        this.prompt = prompt;
        this.system = String.format("""
                You are '%s', a %s.
                Use tools to complete tasks.
                Send results via send_message to 'lead'.
                """, name, role);
        this.history.add(SystemMessage.from(system));
        this.history.add(UserMessage.from(prompt));
        this.client = client != null ? client
                : OpenAiChatModel.builder()
                        .apiKey(System.getenv("OPENAI_API_KEY"))
                        .strictJsonSchema(true)
                        .baseUrl(System.getenv("OPENAI_BASE_URL"))
                        .modelName(System.getenv("OPENAI_MODEL_NAME"))
                        .customParameters(Map.of("reasoning_split", true))
                        .build();
        this.registerTools(additionalTools);
    }

    private void registerTools(Tool[] extraTools) {
        tools.add(new BashTool());
        tools.add(new ReadFileTool());
        tools.add(new WriteFileTool());
        tools.add(new EditFileTool());
        tools.add(new SendMessageToTeammateTool());
        tools.addAll(List.of(extraTools));
        toolSpecifications.addAll(this.tools.stream().map(Tool::getSpecification).toList());
    }

    @Override
    public void run() {
        ConsoleRenderer.getShared().printAiMessage("Agent " + name + "开始共工作.");
        for (int i = 0; i < 10; i++) {
            ChatResponse response = client.chat(ChatRequest.builder()
                    .toolSpecifications(toolSpecifications)
                    .messages(history)
                    .build());
            AiMessage aiMessage = response.aiMessage();
            history.add(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                break;
            }
            for (ToolExecutionRequest toolExeReq : aiMessage.toolExecutionRequests()) {
                Tool handler = ToolManager.handler(toolExeReq.name());
                String toolResult;
                try {
                    toolResult = handler.execute(toolExeReq);
                } catch (RuntimeException e) {
                    toolResult = "Error: tool execution failed: " + e.getMessage();
                }
                history.add(ToolExecutionResultMessage.from(toolExeReq, toolResult));
            }
        }
        ListIterator<ChatMessage> listIterator = history.listIterator(history.size());
        while (listIterator.hasPrevious()) {
            ChatMessage message = listIterator.previous();
            if (message.type() == ChatMessageType.AI) {
                var aiMessage = (AiMessage) message;
                MessageBus.getInstance().send(name, "lead", aiMessage.text(), AgentMsgType.MESSAGE);
                ConsoleRenderer.getShared().printAiMessage("Agent " + name + " 结束");
                return;
            }
        }
    }

    public String getAgentName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getSystem() {
        return system;
    }

}
