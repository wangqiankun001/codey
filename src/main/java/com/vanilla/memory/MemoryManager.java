package com.vanilla.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.util.ChatMessageJsonConvertor;
import com.vanilla.util.ConsoleRenderer;

import cn.hutool.core.util.StrUtil;
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

    private static List<String> listMemoryFiles() {
        File file = dir.toFile();
        if (!file.exists()) {
            return List.of();
        }
        try {
            return Files.list(dir).filter(mem -> !MEMORY_INDEX_NAME.equals(mem.getFileName().toString()))
                    .map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            ConsoleRenderer.getShared().printError("memory load failed.");
            return List.of();
        }
    }

    private static List<Memory> loadAllMemory() {
        return listMemoryFiles().stream().map(MemoryManager::loadMemory).filter(mem -> mem != null).toList();
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

    private static String loadMemoryAsString(String fileName) {
        Path path = dir.resolve(fileName);
        if (!path.toFile().exists()) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            ConsoleRenderer.getShared().printError("读取记忆文件作为字符串失败" + fileName + ".md");
            return "";
        }
    }

    private static Memory parse(String strMemory) {
        if (strMemory == null || strMemory.isBlank()) {
            return null;
        }
        List<String> content = Stream.of(strMemory.split("---")).map(String::trim).filter(StrUtil::isNotBlank).toList();
        if (content.isEmpty()) {
            return null;
        }
        Map<String, String> metadata = new HashMap<>();
        for (String line : content.get(0).lines().toList()) {
            int idx = line.indexOf(": ");
            if (idx <= 0) {
                continue;
            }
            metadata.put(line.substring(0, idx), line.substring(idx + 2));
        }
        String name = metadata.get("name");
        if (name == null || name.isBlank()) {
            return null;
        }
        String description = metadata.get("description");
        if (description == null) {
            description = "";
        }
        MemoryType type = MemoryType.from(metadata.get("type"));
        if (type == null) {
            type = MemoryType.PROJECT;
        }
        String body = content.size() > 1 ? content.get(1) : "";
        return new Memory(name, description, type, body);
    }

    private static String stripJsonWrapper(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        // 剥离 ```json ... ``` 或 ``` ... ``` Markdown 代码块包装
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static MemoryWrapper parseMemoryWrapper(String context, String raw) {
        String cleaned = stripJsonWrapper(raw);
        try {
            return mapper.readValue(cleaned, MemoryWrapper.class);
        } catch (JsonProcessingException e) {
            ConsoleRenderer.getShared().printError(
                    "[" + context + "] ai提取记忆内容格式不符合格式: " + cleaned
                            + " | parseError=" + e.getOriginalMessage());
            return null;
        }
    }

    public static void extractMemory(List<ChatMessage> history, OpenAiChatModel client) {
        String dialogue = history.stream().map(ChatMessageJsonConvertor.INSTANCE::convert)
                .collect(Collectors.joining("\n"));
        if (dialogue == null || dialogue.isBlank()) {
            return;
        }
        String memoryDesc = loadAllMemoryDesc();
        // 模板里有 %s,记忆描述里出现 % 必须转义,否则会抛 UnknownFormatConversionException。
        String safeMemoryDesc = memoryDesc == null ? "" : memoryDesc.replace("%", "%%");
        String prompt = String.format(MEMORY_EXTRACT_PROMPT_TEMPLATE, safeMemoryDesc, dialogue);
        ChatResponse response;
        try {
            response = client.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .responseFormat(MEMORY_RESPONSE_FORMAT)
                    .build());
        } catch (RuntimeException e) {
            ConsoleRenderer.getShared().printError("记忆提取失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (response == null) {
            return;
        }
        MemoryWrapper wrapper = parseMemoryWrapper("extractMemory", response.aiMessage().text());
        if (wrapper == null) {
            return;
        }
        wrapper.memories().forEach(MemoryManager::writeMemory);
    }

    private static void writeMemory(Memory memory) {
        if (memory.name() == null || memory.name().isBlank()) {
            ConsoleRenderer.getShared().printError("记忆名称为空，跳过写入");
            return;
        }
        try {
            Files.createDirectories(dir);
            // 防御：拒绝含路径分隔符或上级引用的文件名，避免写到 .codey 之外。
            String safeName = memory.name().replaceAll("[\\\\/]", "_");
            Path path = dir.resolve(safeName + ".md");
            File file = path.toFile();
            if (!file.exists()) {
                file.createNewFile();
            }
            // 字段都来自 LLM，% 字面量会被 String.format 解析，用 %% 转义。
            String name = memory.name().replace("%", "%%");
            String description = memory.description() == null ? "" : memory.description().replace("%", "%%");
            String body = memory.body() == null ? "" : memory.body().replace("%", "%%");
            String type = memory.type() == null ? MemoryType.PROJECT.getValue() : memory.type().getValue();
            String content = String.format(MEMORY_FILE_CONTENT_TEMPLATE, name, description, type, body);
            Files.writeString(path, content);
        } catch (Exception e) {
            ConsoleRenderer.getShared().printError("记忆文件创建失败: " + memory.name());
            return;
        }
        rebuildIndex();
    }

    private static void rebuildIndex() {
        List<Memory> allMemory = loadAllMemory();
        String memoryIndexContent = allMemory.stream()
                .filter(mem -> mem.name() != null)
                .map(mem -> {
                    String name = mem.name().replace("%", "%%");
                    String desc = mem.description() == null ? "" : mem.description().replace("%", "%%");
                    return String.format("- [%s](%s) - %s", name + ".md", name, desc);
                })
                .collect(Collectors.joining("\n"));
        File file = dir.resolve(MEMORY_INDEX_NAME).toFile();
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                ConsoleRenderer.getShared().printError("文件索引创建失败");
            }
        }
        try {
            Files.writeString(file.toPath(), memoryIndexContent);
        } catch (IOException e) {
            ConsoleRenderer.getShared().printError("文件索引内容写入失败");
        }
    }

    private static String loadAllMemoryDesc() {
        return listMemoryFiles().stream()
                .map(MemoryManager::loadMemory)
                .filter(m -> m != null && m.name() != null)
                .map(m -> String.format("name: %s, description: %s", m.name(), m.description() == null ? "" : m.description()))
                .collect(Collectors.joining("\n"));
    }

    public static String readMemoryIndex() {
        File memoryIndex = dir.resolve("MEMORY.md").toFile();
        if (!memoryIndex.exists()) {
            return null;
        }
        try {
            return Files.readString(memoryIndex.toPath());
        } catch (IOException e) {
            return null;
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
        String prompt = """
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
        ChatResponse response;
        try {
            response = client.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .responseFormat(MEMORY_RESPONSE_FORMAT)
                    .build());
        } catch (RuntimeException e) {
            ConsoleRenderer.getShared().printError("记忆合并失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        MemoryWrapper wrapper = parseMemoryWrapper("consolidateMemories",
                response == null ? null : response.aiMessage().text());
        if (wrapper == null || wrapper.memories() == null || wrapper.memories().isEmpty()) {
            // LLM 没给出任何结果，保留旧记忆，避免被清空。
            return;
        }
        // 先把新记忆写进去，写完再删旧文件，避免 LLM 失败时连原始记忆一起丢。
        wrapper.memories().forEach(MemoryManager::writeMemory);
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !MEMORY_INDEX_NAME.equals(p.getFileName().toString()))
                    .filter(p -> wrapper.memories().stream()
                            .noneMatch(m -> (m.name() + ".md").equals(p.getFileName().toString())))
                    .forEach(p -> {
                        if (!p.toFile().delete()) {
                            ConsoleRenderer.getShared().printError("过期记忆删除失败: " + p.getFileName());
                        }
                    });
        } catch (IOException e) {
            ConsoleRenderer.getShared().printError("过期记忆删除失败");
        }
    }

    public static void injectRelevantMemory(List<ChatMessage> history, OpenAiChatModel client) {
        List<Memory> memories = loadAllMemory();
        if (memories.isEmpty()) {
            return;
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            var mem = memories.get(i);
            stringBuilder.append(String.format("%d: %s - %s\n", i, mem.name(), mem.description()));
        }
        var catalog = stringBuilder.toString();
        var maybeLast = UserMessage.findLast(history);
        if (maybeLast.isEmpty()) {
            return;
        }
        var lastUserMessage = maybeLast.get();
        // 后台任务通知等多 Content 的 UserMessage 没有 singleText();这里把
        // 所有文本片段拼起来,避免抛 "Expecting single text content"。
        String recentText = lastUserMessage.hasSingleText()
                ? lastUserMessage.singleText()
                : lastUserMessage.contents().stream()
                        .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                        .map(dev.langchain4j.data.message.TextContent.class::cast)
                        .map(dev.langchain4j.data.message.TextContent::text)
                        .collect(Collectors.joining("\n"));
        String prompt = String.format("""
                Given the recent conversation and the memory catalog below,
                select the indices of memories that are clearly relevant.
                Return ONLY a array of integers,split by ',' e.g. 0,3.
                If none are relevant, return empty string.

                Recent user conversation:
                %s
                Memory catalog:
                %s
                """, recentText, catalog);
        String relevantIdx = client.chat(prompt);
        if (StrUtil.isBlank(relevantIdx)) {
            return;
        }

        var relevantMemories = Stream.of(relevantIdx.split(",")).filter(StrUtil::isNotBlank)
                .mapToInt(Integer::valueOf).mapToObj(memories::get).toList();
        stringBuilder = new StringBuilder();

        stringBuilder.append("<relevant_memories>");
        stringBuilder.append("\n");
        for (Memory mem : relevantMemories) {
            stringBuilder.append(loadMemoryAsString(mem.name() + ".md"));
            stringBuilder.append("\n\n");
        }
        stringBuilder.append("</relevant_memories>");
        history.add(UserMessage.from(stringBuilder.toString()));
    }
}
