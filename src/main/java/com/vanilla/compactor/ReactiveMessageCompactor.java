package com.vanilla.compactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import com.vanilla.util.ChatMessageJsonConvertor;
import com.vanilla.util.ConsoleRenderer;

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
        ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message);
    }

    /**
     * LLM 二次压缩 + transcript 落盘。
     *
     * <p>每次调用都通过 {@link ConsoleRenderer#printDebug} 输出<b>恰好一行</b>关键日志，
     * 包含：head 压缩前/后条数、tail 保留条数、transcript 落盘结果（成功给文件名，
     * 失败给错误原因）。这样长时间运行时能直观看到每次 reactive 压缩的决策。
     */
    public static List<ChatMessage> reactiveCompact(List<ChatMessage> history, ChatModel client) {
        if (history == null || history.isEmpty()) {
            log("skipped: history is empty");
            return history;
        }
        // 触发 LLM 二次压缩前先落盘 transcript，方便后续排错定位
        TranscriptWrite transcriptWrite = writeTranscript(history);
        // 从末尾向前跳过连续的工具结果消息，定位 head/tail 分界点
        int i = history.size() - 1;
        while (i > 0 && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(i).type())) {
            i--;
        }
        // 上面循环结束时有两种情况：i==0 且仍指向 TOOL_EXECUTION_RESULT，或 i 非 0 且指向非 tool_result。
        // 一次保证 i 是 head 的"尾后"位置。
        int headEnd = i;
        if (headEnd == 0 && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(0).type())) {
            // 整段历史都是工具结果，没有可压缩的 head。
            log("skipped: head=0 (entire history is tool results)");
            return history;
        }
        List<ChatMessage> tail = history.subList(headEnd, history.size());
        List<ChatMessage> compactedHead = LLMMessageCompactor.llmCompact(history.subList(0, headEnd), client);
        history.clear();
        history.addAll(compactedHead);
        history.addAll(tail);

        // 唯一一行关键信息：head 被 LLM 二次压缩 + tail 原样保留 + transcript 落盘结果
        String transcriptPart = transcriptWrite.error != null
                ? ", transcript=FAILED (" + transcriptWrite.error + ")"
                : ", transcript=" + transcriptWrite.path.getFileName();
        log("recompact: head=" + headEnd + " (-> " + compactedHead.size() + "), tail kept=" + tail.size()
                + transcriptPart);
        return history;
    }

    /**
     * 把当前历史写成 {@code .jsonl} transcript 落盘。所有结果都通过返回值表达，
     * 避免内部直接打日志破坏"每次调用只输出 1 行"的约定。
     */
    private static TranscriptWrite writeTranscript(List<ChatMessage> history) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path transcript = Files.createFile(
                    TRANSCRIPT_DIR.resolve(System.currentTimeMillis() + ".jsonl"));
            String content = history.stream()
                    .map(ChatMessageJsonConvertor.INSTANCE::convert)
                    .collect(Collectors.joining("\n"));
            Files.writeString(transcript, content, StandardCharsets.UTF_8);
            return new TranscriptWrite(transcript, null);
        } catch (IOException e) {
            return new TranscriptWrite(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * {@link #writeTranscript} 的返回包：成功路径 + 可选错误信息。失败时 {@link #path} 为 {@code null}。
     */
    private record TranscriptWrite(Path path, String error) {}
}