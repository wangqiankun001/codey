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

    private static final ResponseFormat MEMORY_RESPONSE_FORMAT = ResponseFormat.builder()
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
            .build();

    private static final int CONSOLIDATE_THRESHOLD = 5;

    private static final String MEMORY_INDEX_NAME = "MEMORY.md";

    private static Path dir = Path.of(System.getProperty("user.dir"), ".codey", "memories");

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String MEMORY_EXTRACT_PROMPT_TEMPLATE = """
            Extract user preferences, constraints, or project facts from this dialogue.
            Return a JSON object with a "memories" array. Each array item: {name, type, description, body}.
            - name: short kebab-case identifier (e.g. 'user-preference-tabs')
            - type: one of 'user' (user preference), 'feedback' (guidance),'project' (project fact), 'reference' (external pointer)
            - description: one-line summary for index lookup
            - body: full detail in markdown
            If nothing new or already covered by existing memories, return {"memories": []}.
            直接返回纯 JSON 字符串，不要用 Markdown 代码块（不要带 ```json 包装），不要包含任何解释性文字。
            正确的示例：
            {"memories":[{"name":"user-preference-format-code","type":"user","description":"User prefers to format code after writing it","body":"..."}]}
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

    /**
     * 剥离模型可能包裹在 JSON 外的 Markdown 代码块外壳（```json ... ``` 或 ``` ... ```）。
     * response_format 只能约束模型输出 JSON，但不能强制它去掉 Markdown 外壳。
     */
    private static String stripCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline != -1 && lastFence > firstNewline) {
            trimmed = trimmed.substring(firstNewline + 1, lastFence);
        }
        return trimmed.trim();
    }

    private static List<String> listMemoryFiles() {
        File file = dir.toFile();
        if (!file.exists()) {
            return List.of();
        }
        try {
            return Files.list(dir).filter(mem -> !MEMORY_INDEX_NAME.equals(mem.getFileName().toString()))
                    .map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            System.out.println("memory load failed.");
            return List.of();
        }
    }

    private static List<Memory> loadAllMemory() {
        return listMemoryFiles().stream().map(MemoryManager::loadMemory).toList();
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
                .responseFormat(MEMORY_RESPONSE_FORMAT)
                .build());
        AiMessage aiMessage = response.aiMessage();
        MemoryWrapper wrapper;
        try {
            wrapper = mapper.readValue(aiMessage.text(), MemoryWrapper.class);
        } catch (JsonProcessingException e) {
            System.out.println("ai提取记忆内容格式不符合格式: " + aiMessage.text());
            return;
        }
        wrapper.memories().forEach(m -> writeMemory(m));
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
                    memory.type().getValue(), memory.body());
            Files.writeString(path, content);
        } catch (Exception e) {
            System.out.println("记忆文件创建失败: " + memory.name());
            return;
        }
        rebuildIndex();
    }

    private static void rebuildIndex() {
        List<Memory> allMemory = loadAllMemory();
        String memoryIndexContent = allMemory.stream().map(mem -> {
            return String.format("- [%s](%s) - %s", mem.name() + ".md", mem.name(), mem.description());
        }).collect(Collectors.joining("\n"));
        File file = dir.resolve(MEMORY_INDEX_NAME).toFile();
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                System.out.println("文件索引创建失败");
            }
        }
        try {
            Files.writeString(file.toPath(), memoryIndexContent);
        } catch (IOException e) {
            System.out.println("文件索引内容写入失败");
        }
    }

    private static String loadAllMemoryDesc() {
        return listMemoryFiles().stream().map(MemoryManager::loadMemory).map(m -> {
            return String.format("name: %s, description: %s", m.name(), m.description());
        }).collect(Collectors.joining("\n"));
    }

    public static String readMemoryIndex() {
        File memoryIndex = dir.resolve("MEMORY.md").toFile();
        if (!memoryIndex.exists()) {
            return "<暂时没有记忆>";
        }
        try {
            return Files.readString(memoryIndex.toPath());
        } catch (IOException e) {
            return "<记忆索引读取失败>";
        }
    }

    public static void consolidateMemories(OpenAiChatModel client) {
        List<Memory> memoryList = loadAllMemory();
        if (memoryList.size() < CONSOLIDATE_THRESHOLD) {
            return;
        }
        String catalog = memoryList.stream().map(mem -> {
            return String.format("""
                    ##%s
                    name: %s
                    description: %s
                    body: %s
                    """, mem.name() + ".md", mem.name(), mem.description(), mem.body());
        }).collect(Collectors.joining("\n\n"));
        String propmt = """
                Consolidate the following memory files. Rules:
                1. Merge duplicates into one
                2. Remove outdated/contradicted memories
                3. Keep the total under 30 memories
                4. Preserve important user preferences above all
                type 字段必须使用以下四个枚举值之一：'user' / 'feedback' / 'project' / 'reference'。不要使用 'preference'、'personal' 等其他值。
                直接返回纯 JSON 对象（不要用 Markdown 代码块，不要带 ```json 包装）：
                {"memories": [{"name":"...","type":"user|feedback|project|reference","description":"...","body":"..."}]}
                Each item: {name, type, description, body}.

                """ + catalog;
        ChatResponse response = client.chat(ChatRequest.builder()
                .messages(UserMessage.from(propmt))
                .responseFormat(MEMORY_RESPONSE_FORMAT)
                .build());
        MemoryWrapper wrapper;
        AiMessage aiMessage = response.aiMessage();
        try {
            wrapper = mapper.readValue(aiMessage.text(), MemoryWrapper.class);
        } catch (JsonProcessingException e) {
            System.out.println("ai提取记忆内容格式不符合格式: " + aiMessage.text());
            return;
        }
        try {
            Files.list(dir).forEach(oldMem -> {
                if (!MEMORY_INDEX_NAME.equals(oldMem.getFileName().toString())) {
                    oldMem.toFile().delete();
                }
            });
        } catch (IOException e) {
            System.out.println("过期记忆删除失败");
        }
        wrapper.memories().forEach(MemoryManager::writeMemory);
    }
}
