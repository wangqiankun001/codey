package com.vanilla.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class MemoryUtil {

    private static Path dir = Paths.get(".codey", "memory");

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
        String prompt = String.format("""
                Extract user preferences, constraints, or project facts from this dialogue.
                Return a JSON object with a "memories" array. Each array item: {name, type, description, body}.
                - name: short kebab-case identifier (e.g. 'user-preference-tabs')
                - type: one of 'user' (user preference), 'feedback' (guidance),
                'project' (project fact), 'reference' (external pointer)
                - description: one-line summary for index lookup
                - body: full detail in markdown
                If nothing new or already covered by existing memories, return {"memories": []}.
                Existing memories: %s
                Dialogue: %s
                """, memoryDesc, dialogue);

        ChatResponse response = client.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(JsonSchema.builder()
                                // OpenAI structured outputs require a non-null schema name;
                                // see https://platform.openai.com/docs/guides/structured-outputs
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
        System.out.println(aiMessage);
    }

    private static String loadAllMemoryDesc() {
        return listMemoryFiles().stream().map(MemoryUtil::loadMemory).map(m -> {
            return String.format("name: %s, description: %s", m.name(), m.description());
        }).collect(Collectors.joining("\n"));
    }
}

enum MemoryType {
    USER,
    FEEDBACK,
    PROJECT,
    REFERENCE;

    public static MemoryType from(String type) {
        return Stream.of(values()).filter(e -> e.name().equalsIgnoreCase(type)).findFirst().orElse(null);
    }
}

record Memory(String name, String description, MemoryType type, String body) {
}
