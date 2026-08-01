package com.vanilla.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.util.ChatMessageJsonConvertor;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class MemoryManager {

    private static Path dir = Path.of(System.getProperty("user.dir"), ".codey", "memory");

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String MEMORY_EXTRACT_PROMPT_TEMPLATE = """
            Extract user preferences, constraints, or project facts from this dialogue.
            Return a JSON object with a "memories" array. Each array item: {name, type, description, body}.
            - name: short kebab-case identifier (e.g. 'user-preference-tabs')
            - type: one of 'user' (user preference), 'feedback' (guidance),'project' (project fact), 'reference' (external pointer)
            - description: one-line summary for index lookup
            - body: full detail in markdown
            If nothing new or already covered by existing memories, return {"memories": []}.
            直接返回JSON格式的内容，不要包含其他非相关内容。
            错误示例：
            "```json
            {
                "memories": [
                    {
                    "name": "user-preference-format-code",
                    "type": "user",
                    "description": "User prefers to format code after writing it",
                    "body": "..."
                    }
                ]
            }
            ```"
            正确的示例：
            {
                "memories": [
                    {
                    "name": "user-preference-format-code",
                    "type": "user",
                    "description": "User prefers to format code after writing it",
                    "body": "..."
                    }
                ]
            }
            Existing memories:
            %s
            Current dialogue:
            %s
            """;
    private static final String MEMORY_FILE_CONTENT_TEMPLATE = """
            ---
            name: %s
            description: %s
            type: %s
            ---

            %s
            """;

    private static List<String> listMemoryFiles() {
        File file = dir.toFile();
        if (!file.exists()) {
            return List.of();
        }
        try {
            return Files.list(dir).map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            System.out.println("memory load failed.");
            return List.of();
        }
    }

    private static Memory loadMemory(String fileName) {
        File file = dir.resolve(fileName).toFile();
        if (!file.exists()) {
            return null;
        }
        String strMemory;
        try {
            strMemory = Files.readString(file.toPath());
        } catch (IOException e) {
            return null;
        }
        return parse(strMemory);
    }

    private static Memory parse(String strMemory) {
        List<String> content = Stream.of(strMemory.split("---")).map(String::trim).filter(StrUtil::isNotBlank).toList();
        Map<String, String> metadata = content.get(0).lines()
                .collect(Collectors.toMap(line -> line.split(": ")[0], line -> line.split(": ")[1]));
        return new Memory(metadata.get("name"), metadata.get("description"), MemoryType.from(metadata.get("type")),
                content.get(1));
    }

    public static void extractMemory(List<ChatMessage> history, OpenAiChatModel client) {
        String dialogue = history.stream().map(ChatMessageJsonConvertor.INSTANCE::convert)
                .collect(Collectors.joining("\n"));
        String memoryDesc = loadAllMemoryDesc();
        String prompt = String.format(MEMORY_EXTRACT_PROMPT_TEMPLATE, memoryDesc, dialogue);
        ChatResponse response = client.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(JsonSchema.builder()
                                .name("memory_extraction")
                                .rootElement(JsonObjectSchema.builder()
                                        .addProperty("memories", JsonArraySchema.builder()
                                                .items(JsonObjectSchema.builder()
                                                        .addStringProperty("name")
                                                        .addStringProperty("type")
                                                        .addStringProperty("description")
                                                        .addStringProperty("body")
                                                        .required("name", "type", "description", "body")
                                                        .build())
                                                .build())
                                        .required("memories")
                                        .build())
                                .build())
                        .build())
                .build());
        AiMessage aiMessage = response.aiMessage();
        record MemoryWrapper(List<Memory> memories) {
        }
        MemoryWrapper wrapper;
        try {
            wrapper = mapper.readValue(aiMessage.text(), MemoryWrapper.class);
        } catch (JsonProcessingException e) {
            System.out.println("ai提取记忆内容格式不符合格式: " + aiMessage.text());
            return;
        }
        wrapper.memories.forEach(m -> writeMemory(m));
    }

    private static void writeMemory(Memory memory) {
        try {
            Files.createDirectories(dir);
            Path path = dir.resolve(memory.name() + ".md");
            File file = path.toFile();
            if (!file.exists()) {
                file.createNewFile();
            }
            String content = String.format(MEMORY_FILE_CONTENT_TEMPLATE, memory.name(), memory.description(),
                    memory.type(), memory.body());
            Files.writeString(path, content);
        } catch (Exception e) {
            System.out.println("记忆文件创建失败: " + memory.name());
            return;
        }
    }

    private static String loadAllMemoryDesc() {
        return listMemoryFiles().stream().map(MemoryManager::loadMemory).map(m -> {
            return String.format("name: %s, description: %s", m.name(), m.description());
        }).collect(Collectors.joining("\n"));
    }
}
