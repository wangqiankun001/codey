package com.vanilla.compactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import com.vanilla.util.ChatMessageJsonConvertor;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.ChatModel;

public class ReactiveMessageCompactor {

    /** 落到磁盘的 transcript 子目录（相对当前工作目录）。 */
    private static final Path TRANSCRIPT_DIR = Paths.get(".codey", "transcript");

    /** 用于在终端上区分本压缩器自身输出的前缀。 */
    private static final String LOG_PREFIX = "[reactive] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        System.out.println(LOG_PREFIX + message);
        System.out.flush();
    }

    public static List<ChatMessage> reactiveCompact(List<ChatMessage> history, ChatModel client) {
        writeTranscript(history);
        int i;
        for (i = history.size() - 1;; i--) {
            ChatMessage chatMessage = history.get(i);
            if (!ChatMessageType.TOOL_EXECUTION_RESULT.equals(chatMessage.type())) {
                break;
            }
        }
        List<ChatMessage> tail= history.subList(i, history.size());
        List<ChatMessage> compactedHead = LLMMessageCompactor.llmCompact(history.subList(0, i), client);
        history.clear();
        history.addAll(compactedHead);
        history.addAll(tail);
        return history;
    }

    private static void writeTranscript(List<ChatMessage> history) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path transcript = Files.createFile(
                    TRANSCRIPT_DIR.resolve(System.currentTimeMillis() + ".jsonl"));
            String content = history.stream()
                    .map(ChatMessageJsonConvertor.INSTANCE::convert)
                    .collect(Collectors.joining("\n"));
            Files.writeString(transcript, content, StandardCharsets.UTF_8);
            log("transcript written: path=" + transcript.toAbsolutePath()
                    + ", messages=" + history.size() + ", bytes=" + content.length());
        } catch (IOException e) {
            log("transcript写入失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
