package com.vanilla.compactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * L3 上下文压缩：toolResultBudget
 *
 * <p>把位于历史末尾的连续工具执行结果中超出预算的输出落盘，仅保留
 * {@code <persisted-output>} 短引用与少量预览，避免工具结果占满上下文窗口。
 *
 * <p>该实现按 OpenAI 标准适配：在 langchain4j 中，
 * {@link ToolExecutionResultMessage} 对应 {@code role=tool} 的独立消息，
 * 而不是 Anthropic 风格下嵌在 {@code user} 消息里的 {@code tool_result} 块序列。
 * 等价做法是：定位历史末尾连续的一段 {@code TOOL_EXECUTION_RESULT}，
 * 然后按与 Python 实现相同的"从大到小逐个落盘"策略压缩。
 */
public class BudgetMessageCompactor {

    /** 单条 tool_result 超过该字符数就落盘，仅保留预览。 */
    public static final int PERSIST_THRESHOLD = 15_000; //原教材 30_000

    /** 连续工具结果累计字符预算（超过即开始压缩）。 */
    public static final int MAX_BYTES = 100_000; //原教材 200_000

    /** 落盘后保留的预览长度（字符）。 */
    public static final int PREVIEW_CHARS = 2_000;

    /** 工具结果落盘目录（相对当前工作目录）。 */
    public static final Path TOOL_RESULTS_DIR = Paths.get(".codey","task_outputs", "tool-results");

    /** 单条 tool_result 中无法解析为单一文本的占位 id。 */
    private static final String UNKNOWN_TOOL_ID = "unknown";

    /** 用于在终端上区分压缩器自身输出的前缀。 */
    private static final String LOG_PREFIX = "[budget] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message);
    }

    /**
     * 使用默认 {@link #MAX_BYTES} 预算压缩历史中的工具结果。
     *
     * @param history 聊天消息历史（会被就地修改）
     * @return 同一 {@code history} 引用，便于链式调用
     */
    public static List<ChatMessage> toolResultBudget(List<ChatMessage> history) {
        return toolResultBudget(history, MAX_BYTES);
    }

    /**
     * 把末尾连续的工具执行结果压到 {@code maxBytes} 以内。
     *
     * <ol>
     *   <li>定位最后一段连续的 {@link ChatMessageType#TOOL_EXECUTION_RESULT} 消息
     *       （对应 OpenAI 中附在 assistant 工具调用后的 {@code tool} 角色消息序列）。</li>
     *   <li>统计这些消息中纯文本部分的长度；未超过预算则直接返回。</li>
     *   <li>按长度从大到小，对超过 {@link #PERSIST_THRESHOLD} 的内容调用
     *       {@link #persistLargeOutput(String, String)} 落盘并替换为短引用，
     *       直到总长度回到预算内或没有更多可压缩条目。</li>
     * </ol>
     *
     * <p>无论是否真正发生压缩，每次调用都会通过 {@link ConsoleRenderer#printDebug}
     * 输出 <b>恰好一行</b>关键日志：要么是不满足压缩条件的说明，要么是
     * 压缩前后的字符数对比。这样能在长时间运行的会话里直观看到每次触发的决策。
     */
    public static List<ChatMessage> toolResultBudget(List<ChatMessage> history, int maxBytes) {
        if (history == null || history.isEmpty()) {
            log("skipped: history is empty");
            return history;
        }

        // 1) 定位最后一段连续的 TOOL_EXECUTION_RESULT：[start, end)
        int end = history.size();
        int start = end;
        while (start > 0 && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(start - 1).type())) {
            start--;
        }
        if (start == end) {
            log("skipped: no trailing tool results (history=" + history.size() + " msgs)");
            return history;
        }

        // 2) 仅参与预算的是纯文本结果；多模态/复合 contents 的 text() 会抛异常，直接跳过
        List<Slot> slots = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            ToolExecutionResultMessage msg = (ToolExecutionResultMessage) history.get(i);
            if (msg.hasSingleText()) {
                slots.add(new Slot(i, msg.text().length()));
            }
        }
        if (slots.isEmpty()) {
            log("skipped: no single-text tool results in tail (tail=" + (end - start) + " msgs)");
            return history;
        }

        int originalTotal = slots.stream().mapToInt(Slot::length).sum();
        if (originalTotal <= maxBytes) {
            log("skipped: " + originalTotal + " <= maxBytes " + maxBytes
                    + " (tail=" + slots.size() + " toolResults)");
            return history;
        }

        // 3) 按长度从大到小逐个落盘
        int total = originalTotal;
        int persistedCount = 0;
        String firstPersistError = null;
        List<Slot> ranked = new ArrayList<>(slots);
        ranked.sort(Comparator.comparingInt(Slot::length).reversed());

        for (Slot slot : ranked) {
            if (total <= maxBytes) {
                break;
            }
            if (slot.length() <= PERSIST_THRESHOLD) {
                // 单条已经小于阈值，落盘收益不足以抵消 IO，不处理
                continue;
            }
            ToolExecutionResultMessage original = (ToolExecutionResultMessage) history.get(slot.index());
            String content = original.text();
            String tid = original.id() == null ? UNKNOWN_TOOL_ID : original.id();
            PersistResult result = persistLargeOutput(tid, content);
            if (firstPersistError == null && result.error != null) {
                firstPersistError = result.error;
            }
            if (result.text.length() >= content.length()) {
                // 落盘失败或没缩短，避免死循环
                continue;
            }
            ToolExecutionResultMessage compacted = original.toBuilder()
                .contents(TextContent.from(result.text))
                .build();
            history.set(slot.index(), compacted);
            persistedCount++;

            // 重新累加，保持与 Python 版本 `total = sum(...)` 等价的语义
            int newTotal = 0;
            for (int i = start; i < end; i++) {
                ToolExecutionResultMessage m = (ToolExecutionResultMessage) history.get(i);
                if (m.hasSingleText()) {
                    newTotal += m.text().length();
                }
            }
            total = newTotal;
        }

        // 唯一一行关键信息：压缩的条数 + 字符变化；如果一次都没有成功落盘，
        // 说明虽然超预算但单条都已低于阈值或落盘全部失败，也要输出说明。
        if (persistedCount > 0) {
            int savedChars = Math.max(0, originalTotal - total);
            int percent = originalTotal > 0 ? (int) Math.round(savedChars * 100.0 / originalTotal) : 0;
            String suffix = firstPersistError == null ? "" : " (persist err: " + firstPersistError + ")";
            log("persisted " + persistedCount + " toolResults: " + originalTotal + " -> " + total
                    + " chars (saved " + percent + "%)" + suffix);
        } else {
            String reason = firstPersistError != null
                    ? "persist failed: " + firstPersistError
                    : "no single toolResult exceeded " + PERSIST_THRESHOLD + " chars";
            log("skipped: over budget " + originalTotal + " > " + maxBytes
                    + " but " + reason + " (tail=" + slots.size() + " toolResults)");
        }
        return history;
    }

    /**
     * 把过大的工具输出写入 {@link #TOOL_RESULTS_DIR}，返回
     * {@code <persisted-output>...Preview:...</persisted-output>} 格式的占位文本。
     *
     * <p>对应 Python 实现中的同名函数：
     * <pre>
     * f"&lt;persisted-output&gt;\nFull output: {path}\nPreview:\n{output[:2000]}\n&lt;/persisted-output&gt;"
     * </pre>
     *
     * <p>任何错误都不会直接打日志，而是放进 {@link PersistResult#error}，由调用方
     * 合并到本次压缩的唯一一行日志里，避免破坏"每次调用只输出 1 行"的约定。
     */
    static PersistResult persistLargeOutput(String toolUseId, String output) {
        if (output == null || output.length() <= PERSIST_THRESHOLD) {
            return new PersistResult(output, null);
        }
        try {
            Files.createDirectories(TOOL_RESULTS_DIR);
            String safeId = sanitizeFileName(toolUseId == null ? UNKNOWN_TOOL_ID : toolUseId);
            if (safeId.isEmpty()) {
                safeId = UNKNOWN_TOOL_ID;
            }
            Path path = TOOL_RESULTS_DIR.resolve(safeId + ".txt");
            if (!Files.exists(path)) {
                Files.writeString(path, output, StandardCharsets.UTF_8);
            }
            String preview = output.substring(0, Math.min(PREVIEW_CHARS, output.length()));
            String placeholder = "<persisted-output>\nFull output: " + path.toAbsolutePath()
                    + "\nPreview:\n" + preview + "\n</persisted-output>";
            return new PersistResult(placeholder, null);
        } catch (IOException e) {
            // 落盘失败就原样返回，宁可超预算也不丢内容；错误留给调用方合并到唯一一行日志
            return new PersistResult(output, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 将任意 tool_use_id 规整成安全的文件名片段，避免空 id 或路径分隔符导致路径逃逸。
     */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 只保留字母/数字/点/下划线/连字符，其余替换为下划线
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String sanitized = sb.toString().strip();
        // 防止 ".", ".."，Windows 保留名等
        if (sanitized.equals(".") || sanitized.equals("..")) {
            return UNKNOWN_TOOL_ID;
        }
        return sanitized;
    }

    /** 历史中可压缩工具结果的位置 + 当前文本长度。 */
    private record Slot(int index, int length) {}

    /**
     * {@link #persistLargeOutput} 的返回包：落盘后的占位文本 + 落盘过程产生的
     * 可选错误信息。这样调用方就能把错误合并到当次压缩的唯一一行日志里。
     */
    record PersistResult(String text, String error) {}
}
