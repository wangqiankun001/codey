package com.vanilla.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 用精确的文本匹配修改 UTF-8 文件。
 *
 * <p>默认要求旧文本只出现一次，避免模型误修改多个位置；传入
 * {@code replace_all: true} 时才会替换所有匹配项。</p>
 *
 * <p>安全性保证：</p>
 * <ul>
 *   <li>拒绝空 {@code old_string}(否则会撑爆文件);</li>
 *   <li>{@code replace_all=false} 时要求恰好 1 处匹配;</li>
 *   <li>限制替换后文件体积,防止误改/注入造成的资源耗尽;</li>
 *   <li>通过临时文件 + 原子 rename 写入,保证文件不会被写到一半;
 *       写入完成后对父目录 fsync,降低崩溃时丢文件的概率。</li>
 * </ul>
 */
public class EditFileTool implements Tool {

    private static final String TOOL_NAME = "edit_file";

    /** 替换后文件允许的最大字节数(防止异常膨胀)。 */
    private static final long MAX_RESULT_BYTES = 50L * 1024L * 1024L;

    /** 替换后体积相对原文件允许的最大倍率。 */
    private static final int MAX_SIZE_MULTIPLIER = 10;

    /** 仅供测试覆写绝对上限字节数(避免在测试用例中构造 50MB+ 字符串触发 OOM)。 */
    static volatile long overrideMaxResultBytes = -1L;

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("Replace an exact text snippet in a UTF-8 file. By default the old text must occur exactly once.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "The path of the file to edit")
                        .addStringProperty("old_string", "The exact text to find")
                        .addStringProperty("new_string", "The replacement text")
                        .addBooleanProperty("replace_all", "Whether to replace every occurrence; defaults to false")
                        .required("path", "old_string", "new_string")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        if (request == null || request.arguments() == null) {
            return "Error: tool execution request or arguments cannot be null.";
        }

        final JSONObject arguments;
        try {
            arguments = JSONUtil.parseObj(request.arguments());
        } catch (RuntimeException e) {
            return "Error: invalid tool arguments: " + safeMessage(e);
        }

        String pathArgument = getStringArg(arguments, "path", "file_path");
        if (pathArgument.isEmpty()) {
            return "Error: path cannot be empty.";
        }

        String oldString = getStringArg(arguments, "old_string", "oldString");
        if (oldString.isEmpty()) {
            // String.replace("", anything) 会在每个字符间隙插入内容,
            // 直接拒绝是杜绝 DoS 唯一可靠的方式。
            return "Error: old_string cannot be empty.";
        }

        // 空字符串是合法的替换内容,因此只判断 null,不判断 isBlank()。
        String newString = getStringArg(arguments, "new_string", "newString");
        if (newString == null) {
            return "Error: new_string cannot be null.";
        }

        boolean replaceAll = getBoolArg(arguments, "replace_all", "replaceAll");

        final Path path;
        try {
            path = resolvePath(pathArgument.strip());
        } catch (InvalidPathException e) {
            return "Error: invalid file path: " + safeMessage(e);
        }

        try {
            if (!Files.exists(path)) {
                return "Error: file does not exist: " + path;
            }
            if (!Files.isRegularFile(path)) {
                return "Error: path is not a regular file: " + path;
            }

            String original = Files.readString(path, StandardCharsets.UTF_8);
            // 用 stat 取原文件字节数,避免再次把原文按 UTF-8 编码一遍。
            long originalBytes = Files.size(path);

            int firstIndex = original.indexOf(oldString);
            if (firstIndex < 0) {
                return "Error: old_string was not found in file: " + path;
            }
            int occurrences = countOccurrencesFrom(original, oldString, firstIndex);
            if (!replaceAll && occurrences > 1) {
                return "Error: old_string occurs " + occurrences
                        + " times; set replace_all to true or provide a more specific old_string.";
            }

            String updated = replaceAll
                    ? original.replace(oldString, newString)
                    : replaceFirst(original, oldString, newString, firstIndex);

            // 字节膨胀护栏:同时检查绝对上限与倍率上限。绝对上限优先,
            // 因为它独立于原文件大小,能兜底任何倍率检查漏掉的情况。
            // 这里手算字节数而不分配 byte[],省下接近结果大小的内存。
            long updatedBytes = utf8ByteLength(updated);
            long effectiveCap = overrideMaxResultBytes > 0 ? overrideMaxResultBytes : MAX_RESULT_BYTES;
            if (updatedBytes > effectiveCap) {
                return "Error: resulting file would be " + updatedBytes
                        + " bytes, exceeding the " + effectiveCap + "-byte cap.";
            }
            if (originalBytes > 0 && updatedBytes > originalBytes * MAX_SIZE_MULTIPLIER) {
                return "Error: resulting file is " + updatedBytes + " bytes, which is more than "
                        + MAX_SIZE_MULTIPLIER + "x the original " + originalBytes
                        + " bytes; refusing to write.";
            }

            // 原子写入:写到同目录临时文件,fsync 后 ATOMIC_MOVE 覆盖目标。
            // move 成功后对父目录再 force 一次,确保目录条目也落盘,
            // 防止断电时新文件名丢了但内容还在。出现任何异常都不会污染原文件。
            Path parent = path.getParent();
            Path temp = Files.createTempFile(parent, "edit_file_", ".tmp");
            try {
                Files.writeString(temp, updated, StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Files.move(temp, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(parent);
            } finally {
                Files.deleteIfExists(temp);
            }

            int firstLine = lineOfIndex(original, firstIndex);
            long originalNewBytes = utf8ByteLength(newString);
            long originalOldBytes = utf8ByteLength(oldString);
            long delta = (originalNewBytes - originalOldBytes) * occurrences;

            return "Successfully replaced " + occurrences + " occurrence"
                    + (occurrences == 1 ? "" : "s") + " in " + path
                    + " (first match at line " + firstLine
                    + ", byte delta " + (delta >= 0 ? "+" : "") + delta
                    + ", size " + originalBytes + " -> " + updatedBytes + " bytes).";
        } catch (IOException e) {
            return "Error: failed to edit file '" + path + "': " + safeMessage(e);
        } catch (SecurityException e) {
            return "Error: permission denied when editing file '" + path + "'.";
        }
    }

    /** 从 {@code fromIndex} 起统计 {@code target} 在 {@code text} 中出现的次数。 */
    private static int countOccurrencesFrom(String text, String target, int fromIndex) {
        int count = 0;
        int idx = fromIndex;
        while ((idx = text.indexOf(target, idx)) >= 0) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private static String replaceFirst(String text, String target, String replacement, int index) {
        if (index < 0) {
            // 调用方已通过 countOccurrences 断言至少 1 次匹配;这里只是兜底,
            // 避免在极端字符比较条件下抛 StringIndexOutOfBoundsException。
            return text;
        }
        return new StringBuilder(text.length() + replacement.length() - target.length())
                .append(text, 0, index)
                .append(replacement)
                .append(text, index + target.length(), text.length())
                .toString();
    }

    /** 计算字符索引 {@code index} 所在的 1-based 行号。 */
    private static int lineOfIndex(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Path resolvePath(String pathArgument) {
        Path path = Path.of(pathArgument);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize();
    }

    /**
     * 读取字符串参数,兼容 snake_case / camelCase 两种命名。
     * 所有键都为 null 时返回 {@code null};否则返回首个非 null 值。
     */
    private static String getStringArg(JSONObject arguments, String... keys) {
        for (String key : keys) {
            String value = arguments.getStr(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean getBoolArg(JSONObject arguments, String... keys) {
        for (String key : keys) {
            Boolean value = arguments.getBool(key);
            if (value != null) {
                return value;
            }
        }
        return false;
    }

    /**
     * 尝试对目录做 fsync,确保目录条目(包含子文件/子目录名)落盘。
     * 某些 JVM/文件系统不支持目录 fsync(如 FAT32),此时静默降级。
     */
    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 不支持目录 fsync 的平台或文件系统,跳过即可。
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /** 计算 String 按 UTF-8 编码后的字节数,不分配中间 byte[]。 */
    private static long utf8ByteLength(String text) {
        long count = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                count += 1;
            } else if (c < 0x800) {
                count += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                // 高代理 + 低代理 → 4 字节;同时吃掉低代理,避免被单独算成 3 字节。
                count += 4;
                i++;
            } else {
                count += 3;
            }
        }
        return count;
    }
}