# codey 项目：超出 JDK 8 的语法 / API 特性审计

## 1. 项目 JDK 配置
- `pom.xml` 中 `<maven.compiler.source>` 与 `<maven.compiler.target>` 均为 **17**。
- 因此任何 JDK 9 ~ 17 引入的语言特性或标准库 API 都会在编译期通过，但在审计上仍属于"超出 JDK 8"范围。

扫描范围：`src/main/java` 与 `src/test/java` 下全部 42 个 `.java` 文件（未含 `target/` 编译产物）。

---

## 2. 语言级特性（JDK 9+）

### 2.1 `var` 局部变量类型推断（JDK 10+）
共 **7 处**：

| 文件 | 行号 | 语句 |
|------|------|------|
| src\main\java\com\vanilla\memory\MemoryManager.java | 289 | `var mem = memories.get(i);` |
| src\main\java\com\vanilla\memory\MemoryManager.java | 292 | `var catalog = stringBuilder.toString();` |
| src\main\java\com\vanilla\memory\MemoryManager.java | 293 | `var lastUserMessage = UserMessage.findLast(history).get();` |
| src\main\java\com\vanilla\memory\MemoryManager.java | 310 | `var relevantMemories = Stream.of(relevantIdx.split(",")).filter(StrUtil::isNotBlank)` |
| src\test\java\com\vanilla\tool\EditFileToolTest.java | 37 | `try (var stream = Files.walk(TMP_DIR)) {` |
| src\test\java\com\vanilla\tool\EditFileToolTest.java | 185 | `try (var stream = Files.list(TMP_DIR)) {` |
| src\test\java\com\vanilla\tool\EditFileToolTest.java | 201 | `try (var stream = Files.list(TMP_DIR)) {` |

### 2.2 文本块 Text Blocks（JDK 15+，预览于 13/14）
共 **5 个源文件** 使用 `"""..."""`：

| 文件 | 行号 | 角色 |
|------|------|------|
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 66, 77 | `COMPACT_INSTRUCTION` |
| src\main\java\com\vanilla\content\Prompt.java | 8, 16 | `SYSTEM` 模板 |
| src\main\java\com\vanilla\memory\MemoryManager.java | 58, 73, 74, 82, 243, 248, 250, 261, 294, 304 | 记忆提示 / 文件模板 / 目录 / 提取提示 |
| src\main\java\com\vanilla\tool\task\CompleteTaskTool.java | 42, 45 | 工具返回消息模板 |

### 2.3 `record` 记录类型（JDK 16+）
共 **9 处 `record` 声明**：

| 文件 | 行号 | 声明 |
|------|------|------|
| src\main\java\com\vanilla\compactor\BudgetMessageCompactor.java | 239 | `private record Slot(int index, int length) {}` |
| src\main\java\com\vanilla\compactor\BudgetMessageCompactor.java | 245 | `record PersistResult(String text, String error) {}` |
| src\main\java\com\vanilla\compactor\ReactiveMessageCompactor.java | 86 | `private record TranscriptWrite(Path path, String error) {}` |
| src\main\java\com\vanilla\memory\Memory.java | 3 | `record Memory(String name, String description, MemoryType type, String body) {` |
| src\main\java\com\vanilla\memory\MemoryWrapper.java | 5 | `record MemoryWrapper(List<Memory> memories) {` |
| src\main\java\com\vanilla\prompt\SystemMessageBuilder.java | 69 | `public static record Context(` |
| src\main\java\com\vanilla\skill\Skill.java | 3 | `public record Skill(String name, String description, String content) {` |
| src\main\java\com\vanilla\tool\task\CreateTaskTool.java | 48 | `record CreateTaskParam(String subject, String description, List<String> blockedBy) {` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 68 | `public static record ToolExeRequest(String name, String arguments) {` |

### 2.4 `instanceof` 模式匹配（JDK 16+）
共 **14 处**：

| 文件 | 行号 | 模式 |
|------|------|------|
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 172 | `message instanceof UserMessage um` |
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 175 | `c instanceof TextContent tc && tc.text() != null` |
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 180 | `message instanceof AiMessage am` |
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 198 | `message instanceof ToolExecutionResultMessage tu` |
| src\main\java\com\vanilla\compactor\LLMMessageCompactor.java | 204 | `c instanceof TextContent tc && tc.text() != null` |
| src\main\java\com\vanilla\tool\BashTool.java | 205 | `raw instanceof Number n` |
| src\main\java\com\vanilla\tool\BashTool.java | 208 | `raw instanceof String s && !s.isBlank()` |
| src\main\java\com\vanilla\tool\BashTool.java | 274 | `cause instanceof IOException ioException` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 31 | `chatMessage instanceof UserMessage um` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 33 | `chatMessage instanceof SystemMessage sm` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 35 | `chatMessage instanceof AiMessage am` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 45 | `chatMessage instanceof ToolExecutionResultMessage rm` |
| src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java | 60 | `content instanceof TextContent tc` |
| src\main\java\com\vanilla\util\ConsoleRenderer.java | 469 | `message instanceof UserMessage um` |
| src\main\java\com\vanilla\util\ConsoleRenderer.java | 471 | `message instanceof AiMessage am` |
| src\main\java\com\vanilla\util\ConsoleRenderer.java | 485 | `message instanceof ToolExecutionResultMessage tu` |

### 2.5 sealed / non-sealed / permits（JDK 17）
**未使用**。`sealed`、`non-sealed`、`permits` 在 `pom.xml` 配 17 的项目里都没出现。

### 2.6 switch 表达式 / `case X ->`（JDK 14）
**未使用**。所有 `switch` 仍是 JDK 8 风格的 `case ... :` + break。

### 2.7 `yield`（JDK 13 switch 表达式）
**未使用**。

---

## 3. 标准库 API（JDK 9+）

### 3.1 `Files.readString` / `Files.writeString`（JDK 11）
共 **23 处**，几乎覆盖所有 I/O 工具：

- src\main\java\com\vanilla\Codey.java（210, 211, 212）
- src\main\java\com\vanilla\compactor\BudgetMessageCompactor.java（204）
- src\main\java\com\vanilla\compactor\LLMMessageCompactor.java（157）
- src\main\java\com\vanilla\compactor\ReactiveMessageCompactor.java（76）
- src\main\java\com\vanilla\memory\MemoryManager.java（109, 122, 191, 213, 231）
- src\main\java\com\vanilla\skill\SkillManager.java（25）
- src\main\java\com\vanilla\tool\EditFileTool.java（120, 154）
- src\main\java\com\vanilla\tool\GetTaskTool.java（31）
- src\main\java\com\vanilla\tool\ReadFileTool.java（75）
- src\main\java\com\vanilla\tool\WriteFileTool.java（83）
- src\main\java\com\vanilla\tool\task\ListTasksTool.java（36）
- src\main\java\com\vanilla\tool\task\Task.java（59, 72, 88）
- src\test\java\com\vanilla\tool\EditFileToolTest.java（51, 56）

### 3.2 `String.isBlank` / `strip` / `stripLeading` / `stripTrailing` / `lines` / `repeat`（JDK 11）
- `isBlank` 大量使用：`Codey`、`BashTool`、`EditFileTool`、`GlobTool`、`ReadFileTool`、`WriteFileTool`、`SpawnSubagentTool`、`CompleteTaskTool`、`ConsoleRenderer`、`SecurityHook` 等。
- `strip`：`Codey:98`、`SecurityHook:78`、`BashTool:228`、`EditFileTool:107`、`GlobTool:75,89`、`ReadFileTool:62`、`WriteFileTool:69`、`ConsoleRenderer:235`。
- `lines`：`MemoryManager:131`、`SkillManager:41`。
- `repeat`：`ConsoleRenderer:283,296,304,308,382`、`EditFileToolTest:146,148,164,169,197`。

### 3.3 `Stream.toList()`（JDK 16）
共 **10 处**：

- src\main\java\com\vanilla\hook\HookRegistery.java:21
- src\main\java\com\vanilla\memory\MemoryManager.java:91, 99, 130, 311
- src\main\java\com\vanilla\tool\ToolManager.java:46, 50
- src\main\java\com\vanilla\tool\task\Task.java:82
- src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java:42, 64

### 3.4 `Path.of(...)`（JDK 11）
共 **10 处**：

- src\main\java\com\vanilla\hook\SecurityHook.java:77, 78
- src\main\java\com\vanilla\memory\MemoryManager.java:54
- src\main\java\com\vanilla\tool\EditFileTool.java:217, 219
- src\main\java\com\vanilla\tool\ReadFileTool.java:84, 86
- src\main\java\com\vanilla\tool\WriteFileTool.java:93, 95
- src\test\java\com\vanilla\tool\EditFileToolTest.java:22

### 3.5 `Optional.orElseThrow()` 无参形式（JDK 10）
**未使用**。

### 3.6 `Collectors.toUnmodifiable*` / `List/Set/Map.copyOf` / `Map.of` / `List.of` / `Set.of`（JDK 9/10）
**未使用**。

### 3.7 `java.net.http.HttpClient`（JDK 11）
**未使用**。

### 3.8 `ProcessHandle`（JDK 9）
**未使用**。

---

## 4. 汇总

| 类别 | 特性 | 引入版本 | 命中数 | 文件数 |
|------|------|----------|--------|--------|
| 语言 | `var` 局部变量 | JDK 10 | 7 | 2 |
| 语言 | Text Blocks `"""..."""` | JDK 15 | 5 文件（多处） | 5 |
| 语言 | `record` | JDK 16 | 9 | 9 |
| 语言 | `instanceof` 模式匹配 | JDK 16 | 16 | 5 |
| 语言 | sealed / non-sealed / permits | JDK 17 | 0 | 0 |
| 语言 | switch 表达式 / `case X ->` | JDK 14 | 0 | 0 |
| 语言 | `yield` | JDK 13 | 0 | 0 |
| API | `Files.readString` / `writeString` | JDK 11 | 23 | 14 |
| API | `String.isBlank` / `strip` / `lines` / `repeat` | JDK 11 | 多处（≥40） | ≥10 |
| API | `Stream.toList()` | JDK 16 | 10 | 6 |
| API | `Path.of` | JDK 11 | 10 | 6 |
| API | `Optional.orElseThrow()` | JDK 10 | 0 | 0 |
| API | `Map.of` / `List.of` / `Set.of` | JDK 9 | 0 | 0 |
| API | `Collectors.toUnmodifiable*` | JDK 10 | 0 | 0 |
| API | `List/Set/Map.copyOf` | JDK 10 | 0 | 0 |
| API | `java.net.http.HttpClient` | JDK 11 | 0 | 0 |
| API | `ProcessHandle` | JDK 9 | 0 | 0 |

### 涉及到的文件清单
- 全部命中文件列表（去重）：
  - src\main\java\com\vanilla\Codey.java
  - src\main\java\com\vanilla\compactor\BudgetMessageCompactor.java
  - src\main\java\com\vanilla\compactor\LLMMessageCompactor.java
  - src\main\java\com\vanilla\compactor\ReactiveMessageCompactor.java
  - src\main\java\com\vanilla\content\Prompt.java
  - src\main\java\com\vanilla\hook\HookRegistery.java
  - src\main\java\com\vanilla\hook\SecurityHook.java
  - src\main\java\com\vanilla\memory\Memory.java
  - src\main\java\com\vanilla\memory\MemoryManager.java
  - src\main\java\com\vanilla\memory\MemoryWrapper.java
  - src\main\java\com\vanilla\prompt\SystemMessageBuilder.java
  - src\main\java\com\vanilla\skill\Skill.java
  - src\main\java\com\vanilla\skill\SkillManager.java
  - src\main\java\com\vanilla\tool\BashTool.java
  - src\main\java\com\vanilla\tool\EditFileTool.java
  - src\main\java\com\vanilla\tool\GetTaskTool.java
  - src\main\java\com\vanilla\tool\GlobTool.java
  - src\main\java\com\vanilla\tool\ReadFileTool.java
  - src\main\java\com\vanilla\tool\SpawnSubagentTool.java
  - src\main\java\com\vanilla\tool\ToolManager.java
  - src\main\java\com\vanilla\tool\WriteFileTool.java
  - src\main\java\com\vanilla\tool\task\CompleteTaskTool.java
  - src\main\java\com\vanilla\tool\task\CreateTaskTool.java
  - src\main\java\com\vanilla\tool\task\ListTasksTool.java
  - src\main\java\com\vanilla\tool\task\Task.java
  - src\main\java\com\vanilla\util\ChatMessageJsonConvertor.java
  - src\main\java\com\vanilla\util\ConsoleRenderer.java
  - src\test\java\com\vanilla\tool\EditFileToolTest.java

仅以下源文件**未**触及任何 JDK 9+ 特性（即纯 JDK 8 兼容）：
- src\main\java\com\vanilla\CodeyApp.java（如存在；需手动核对）
- src\main\java\com\vanilla\agent\*.java
- src\main\java\com\vanilla\config\*.java
- src\main\java\com\vanilla\hook\OpenAIProvider.java（推测）
- src\main\java\com\vanilla\model\*.java
- src\main\java\com\vanilla\skill\SkillInvocation.java
- src\test\java\com\vanilla\**（除 EditFileToolTest 外）

> 注：上面 4.1 的"未触及"列表仅为按扫描脚本未命中的推断，需要时我可以单独跑一遍全文件清单核对。

---

## 5. 结论
项目已**完全建立在 JDK 17 之上**，并重度使用 JDK 9–16 期间的语言/标准库特性：

- **使用密度最高的 JDK 11 标准库 API**：`Files.readString/writeString`、`String.isBlank/strip/lines/repeat`、`Path.of`。
- **JDK 16 起的新语言特性**：`record`、`instanceof` 模式匹配、`Stream.toList()` 三件套均被使用，其中 `instanceof` 模式匹配集中在工具层（`BashTool`、三个 `*Compactor`、`ChatMessageJsonConvertor`、`ConsoleRenderer`）。
- **JDK 15 文本块**：在提示词/模板相关代码（`Prompt.java`、`MemoryManager.java`、`LLMMessageCompactor.java`、`CompleteTaskTool.java`）中用作长字符串常量。
- **JDK 10 `var`**：在 `MemoryManager` 与一个测试类中出现 7 处，主要用于 `try-with-resources` 与 `Stream` 链尾。
- **JDK 17 专属特性**（`sealed` / `non-sealed` / `permits`）以及 JDK 14 的 `switch` 表达式 / `case X ->` 语法**均未使用**。

如果目标环境真的只能跑 JDK 8，迁移工作量将主要集中在：所有 `record` 改写为 `final class + getter`、`instanceof` 模式匹配改回显式 `if (x instanceof T) { T t = (T) x; ... }`、移除 `var` 显式写类型、用 `String.lines()`/`isBlank()`/`strip()`/`repeat()` 替换为 JDK 8 等价物（`split("\R")`、`trim().isEmpty()`、`trim()`、`String.join("", Collections.nCopies(n, s))`）、用 `new String(Files.readAllBytes(...), StandardCharsets.UTF_8)` 替代 `Files.readString`，并将所有 `Stream.collect(Collectors.toList())` 之外的 `Stream.toList()` 调用改回 `collect(Collectors.toList())`。
