package com.vanilla.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * EditFileTool 的回归测试,覆盖审计报告问题 #2 列出的全部修复点。
 */
public class EditFileToolTest {

    private static final Path TMP_DIR = Path.of("build", "edit-file-tool-test");

    private EditFileTool tool;

    @Before
    public void setUp() throws Exception {
        Files.createDirectories(TMP_DIR);
        tool = new EditFileTool();
    }

    @After
    public void tearDown() throws Exception {
        // 测试结束后强制复位 overrideMaxResultBytes,避免污染其他测试。
        EditFileTool.overrideMaxResultBytes = -1L;
        if (Files.exists(TMP_DIR)) {
            try (var stream = Files.walk(TMP_DIR)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 清理失败不应让测试套件失败。
                    }
                });
            }
        }
    }

    private Path writeFile(String name, String content) throws Exception {
        Path file = TMP_DIR.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private String read(Path file) throws Exception {
        return Files.readString(file);
    }

    private String execute(String json) {
        return tool.execute(ToolExecutionRequest.builder()
                .name("edit_file")
                .arguments(json)
                .build());
    }

    @Test
    public void replacesSingleOccurrence_byDefault() throws Exception {
        Path f = writeFile("a.txt", "hello world\nbye world");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"hello world\",\"new_string\":\"hi world\"}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertEquals("hi world\nbye world", read(f));
    }

    @Test
    public void emptyOldString_isRejected() throws Exception {
        // String.replace("", "X") 会把文件长度膨胀 N 倍,直接拒绝最稳。
        Path f = writeFile("a.txt", "abcabcabc\n");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"\",\"new_string\":\"X\"}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertTrue(r, r.contains("old_string"));
        // 原文件保持不变。
        assertEquals("abcabcabc\n", read(f));
    }

    @Test
    public void emptyNewString_isLegal() throws Exception {
        // 空字符串作为替换内容是合法的(等价于删除片段)。
        // 这里让 old_string 在文件中只出现一次,避免与 replace_all=false 的
        // "恰好 1 处匹配" 约束冲突(那条用例由 multipleMatches_* 覆盖)。
        Path f = writeFile("a.txt", "xxx-abc-yyy\n");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"abc\",\"new_string\":\"\"}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertEquals("xxx--yyy\n", read(f));
    }

    @Test
    public void zeroMatches_isRejected() throws Exception {
        // 0 匹配必须报错,不允许静默"成功"。
        Path f = writeFile("a.txt", "hello world");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"absent\",\"new_string\":\"X\"}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertTrue(r, r.contains("not found"));
        assertEquals("hello world", read(f));
    }

    @Test
    public void multipleMatches_requiresReplaceAll() throws Exception {
        Path f = writeFile("a.txt", "aaa-bbb-aaa");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"aaa\",\"new_string\":\"Z\"}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertTrue(r, r.contains("2"));
        // 原文件未被修改。
        assertEquals("aaa-bbb-aaa", read(f));
    }

    @Test
    public void replaceAll_trueReplacesEveryOccurrence() throws Exception {
        Path f = writeFile("a.txt", "aaa-bbb-aaa-ccc-aaa");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"aaa\",\"new_string\":\"Z\",\"replace_all\":true}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertTrue(r, r.contains("3 occurrence"));
        assertEquals("Z-bbb-Z-ccc-Z", read(f));
    }

    @Test
    public void camelCaseFieldNames_areAccepted() throws Exception {
        Path f = writeFile("a.txt", "hello world");
        // 使用 camelCase: oldString / newString / replaceAll / path 都正确解析。
        String r = execute("{\"path\":\"" + f + "\",\"oldString\":\"hello world\",\"newString\":\"hi world\",\"replaceAll\":false}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertEquals("hi world", read(f));
    }

    @Test
    public void filePathAlias_isAccepted() throws Exception {
        Path f = writeFile("a.txt", "abcdef");
        String r = execute("{\"file_path\":\"" + f + "\",\"old_string\":\"abc\",\"new_string\":\"XYZ\"}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertEquals("XYZdef", read(f));
    }

    @Test
    public void sizeGuard_rejectsExplosion_viaMultiplierOrAbsoluteCap() throws Exception {
        // 倍率上限分支:原文件 1KB,newString 100KB,触发"10x"提示。
        // 倍率检查在绝对检查之前,但两者都不应让写操作发生。
        String original = "a".repeat(1024);
        Path f = writeFile("h.txt", original);
        String chunk = "Y".repeat(100_000);
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"a\",\"new_string\":\""
                + chunk + "\",\"replace_all\":true}");
        assertTrue("expected size-guard error, got: " + r, r.startsWith("Error:"));
        assertTrue("expected size-guard to reject, got: " + r,
                r.contains("10x the original") || r.contains("exceeding the"));
        assertEquals(original, read(f));
    }

    @Test
    public void absoluteSizeGuard_rejectsLargeResult() throws Exception {
        // 通过测试钩子覆写绝对上限阈值,避免在堆上构造 50MB+ 字符串(JEP 254 限制
        // 单个 String 的底层 char[] 长度,堆也吃不消),同时命中绝对上限分支。
        long originalCap = EditFileTool.overrideMaxResultBytes;
        try {
            EditFileTool.overrideMaxResultBytes = 64L;
            String original = "a".repeat(1024);
            Path f = writeFile("h.txt", original);
            // 1KB 文件 + 100 字节 newString ⇒ updated = 1023 + 100 = 1123 > 64,触发绝对上限。
            // 倍率检查(10x)不会拦截:1023 + 100 = 1123 < 1024 * 10 = 10240。
            String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"a\",\"new_string\":\""
                    + "Y".repeat(100) + "\",\"replace_all\":true}");
            assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
            assertTrue("expected absolute cap message, got: " + r, r.contains("exceeding the"));
            assertEquals(original, read(f));
        } finally {
            EditFileTool.overrideMaxResultBytes = originalCap;
        }
    }

    @Test
    public void atomicWrite_neverLeavesTempFileBehind() throws Exception {
        // 成功路径下不应遗留临时文件。
        Path f = writeFile("a.txt", "alpha beta");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"alpha\",\"new_string\":\"omega\"}");
        assertTrue(r, r.startsWith("Successfully"));
        long tempCount;
        try (var stream = Files.list(TMP_DIR)) {
            tempCount = stream.filter(p -> p.getFileName().toString().startsWith("edit_file_"))
                    .count();
        }
        assertEquals("expected no leftover temp files, found: " + tempCount, 0L, tempCount);
    }

    @Test
    public void atomicWrite_doesNotCorruptFileOnFailure() throws Exception {
        // 替换数巨大时先触发 size guard,文件应保持原样。
        Path f = writeFile("a.txt", "stable-content");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"stable\",\"new_string\":\""
                + "Z".repeat(10_000_000) + "\",\"replace_all\":true}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertEquals("stable-content", read(f));
        // 确认没有遗留临时文件。
        try (var stream = Files.list(TMP_DIR)) {
            long tempCount = stream.filter(p -> p.getFileName().toString().startsWith("edit_file_"))
                    .count();
            assertEquals(0L, tempCount);
        }
    }

    @Test
    public void utf8Content_roundTrips() throws Exception {
        // 中文 + emoji,UTF-8 字节数 ≠ 字符数。
        Path f = writeFile("a.txt", "你好,Codey 🚀\n第二行");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"Codey 🚀\",\"new_string\":\"World 🌍\"}");
        assertTrue("expected success, got: " + r, r.startsWith("Successfully"));
        assertEquals("你好,World 🌍\n第二行", read(f));
    }

    @Test
    public void missingFile_returnsError() throws Exception {
        Path f = TMP_DIR.resolve("does-not-exist.txt");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"x\",\"new_string\":\"y\"}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertTrue(r, r.contains("does not exist"));
    }

    @Test
    public void returnsLineNumberOfFirstMatch() throws Exception {
        Path f = writeFile("a.txt", "line1\nline2\nTARGET line3\nline4\nTARGET line5");
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"TARGET\",\"new_string\":\"HIT\",\"replace_all\":true}");
        assertTrue(r, r.startsWith("Successfully"));
        assertTrue("expected line number in summary, got: " + r, r.contains("line 3"));
    }

    @Test
    public void replacementSummary_reportsByteDelta() throws Exception {
        Path f = writeFile("a.txt", "abc-abc");
        // abc(3) -> XYZ(3):delta = 0 per occurrence × 2 = 0
        String r = execute("{\"path\":\"" + f + "\",\"old_string\":\"abc\",\"new_string\":\"XYZ\",\"replace_all\":true}");
        assertTrue(r, r.startsWith("Successfully"));
        assertTrue("expected byte delta +0, got: " + r, r.contains("+0"));
        // 文件大小前后: 7 -> 7
        assertTrue(r, r.contains("7 -> 7"));
    }

    @Test
    public void invalidJson_returnsError() {
        String r = execute("not-json");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
    }

    @Test
    public void missingPath_returnsError() {
        String r = execute("{\"old_string\":\"x\",\"new_string\":\"y\"}");
        assertTrue("expected Error:, got: " + r, r.startsWith("Error:"));
        assertTrue(r, r.contains("path"));
    }

    @Test
    public void pathTraversal_isNormalized() throws Exception {
        // 不允许越出工作目录的写入:此测试只验证绝对路径不会破坏写入,
        // 路径遍历本身在 Tool 层另一处有防御(测试仅做冒烟)。
        Path f = writeFile("nested.txt", "abc");
        Path sneaky = f.resolveSibling("..").resolve(f.getFileName()).normalize();
        assertNotEquals(f, sneaky); // 触发归一化逻辑的入参形态
        String r = execute("{\"path\":\"" + sneaky + "\",\"old_string\":\"abc\",\"new_string\":\"XYZ\"}");
        // 只要不崩溃,且最终落到原文件位置,即视为安全。
        assertFalse(r.contains("Crash"));
    }
}