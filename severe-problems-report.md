# Codey 项目严重问题审计报告

> 审计范围：`/Users/w11814/Desktop/java/codey-main` 全量源码（34 个 `.java` 文件，约 3356 LOC）+ `pom.xml`
> 审计方法：静态阅读 + 控制流/数据流分析 + 边界条件推演
> 报告语言：中文（路径、代码、API 名保持英文）
> 审计时间：2026-01（基于当前 HEAD 源码）

---

## Top 5 严重问题

### 🔴 1. 源码硬编码 API Key 泄漏（CRITICAL）

**位置**：`src/main/java/com/vanilla/tool/SpawnSubagentTool.java:91-95`

```java
apiKey = "sk-cp-RZhJK2wUGo-b2m18glB-pAyIG6X2-phMbLOSKFiONzBgW16K68UVoU3B7Ir7VOwo02KzJHyr5v6Uijst-jl4Lfx0XCjsVHtDbjFOP_k6FWRJxvDAnSzgbBc";
baseUrl = "https://api.minimaxi.com/v1";
modelName = "MiniMax-M3";
```

**严重度与原因**：CRITICAL。明文凭证写死在源代码里 —— 即便是测试 key，也违反 12-factor 与 secrets 管理原则，且**真实存在于 git 历史**（任何曾 `git clone`/`git pull` 该仓库的人都能拿到），无法通过简单的删除 commit 彻底回滚（已被 GitHub/GitLab/任何镜像 fork/缓存）。`baseUrl` 指向 `api.minimaxi.com` 表明这是一个第三方供应商凭证，一旦被滥用可能产生真实计费，且 key 泄露面 = 任何能读源码的人 + 任何构建产物（jar/war/docker image）的消费者。

**复现 / 影响**：

```bash
git log --all -p -- src/main/java/com/vanilla/tool/SpawnSubagentTool.java | grep "sk-cp-"
# 直接输出明文 key，无需任何认证
```

即使 key 已轮换/失效：

- 历史 commit 仍是公开的泄露点（GitHub 不会主动清理 fork）；
- 任何包含此 jar 的 release/docker image 都暴露了 key；
- 攻击者可向 `https://api.minimaxi.com/v1` 用此 key 模拟合法请求，造成**计费损失**或**审计日志污染**。

**修复建议**：

1. **立刻**到供应商控制台**轮换该 API key**（这是最紧急的一步 —— 删代码之前先废 key）。
2. 将 key 改为从环境变量读取：
   ```java
   String apiKey = System.getenv("MINIMAX_API_KEY");
   if (apiKey == null || apiKey.isBlank()) {
       throw new IllegalStateException("MINIMAX_API_KEY not set");
   }
   ```
   同理 `baseUrl`、`modelName` 用环境变量或配置文件（且配置文件加入 `.gitignore`）。
3. 从 git 历史中清除：`git filter-repo --invert-paths --path src/main/java/com/vanilla/tool/SpawnSubagentTool.java`（或 `bfg-repo-cleaner`），随后 force-push 并通知所有协作者 rebase。
4. 接入 secret scanner 作为 pre-commit / CI gate（如 `gitleaks`、`trufflehog`），防止再次泄漏。
5. 强制所有外部凭证经统一 `SecretsProvider` 接口注入，禁止任何 `String` 字面量出现 `sk-`/`sk-cp-`/AWS key 等模式（建议添加 Checkstyle/Spotless 规则）。

---

### 🔴 2. `ToolManager.subagentToolSpecifications` 字符串比较 bug（HIGH）

**位置**：`src/main/java/com/vanilla/tool/ToolManager.java:39`

```java
.filter(tool -> tool.name() != "task")
```

**严重度与原因**：HIGH。Java 中 `!=` 对 `String` 是**引用相等性**（identity）比较，而不是内容相等。JVM 在字符串字面量上启用了字符串驻留（String Interning），所以 `"task" == "task"` 通常返回 `true` —— 但这**不是规范保证**，依赖了 JDK 实现的优化行为。一旦 `Tool.name()` 返回值是 `new String(...)`（例如经过反序列化、JSON 解析、跨 classloader），驻留假设就失效，过滤会**放行名为 `task` 的工具**，子 Agent 就能 spawn 自己。

**复现 / 影响**：

```java
String a = new String("task");   // 不驻留
String b = "task";                // 驻留
System.out.println(a != b);       // true（引用不同）
```

在本项目中：

- 子 Agent 拿到包含 `SpawnSubagentTool`（其 `name()` 为 `"task"`）的工具列表 → 递归生成子 Agent → **资源耗尽（token、进程、文件描述符）/ 死循环**。
- 在某些 JVM 参数下（如 G1 启用 string deduplication 关闭、或反射构造 `Tool`）几乎必然命中。

**修复建议**：

```java
.filter(tool -> !"task".equals(tool.name()))
```

或更明确：

```java
private static final String SPAWN_TOOL_NAME = "task";
...
.filter(tool -> !SPAWN_TOOL_NAME.equals(tool.name()))
```

附加建议：

- 在 `ToolManager` 维护一个 `Set<String> BLOCKED_TOOLS`，而非一行 lambda 中硬编码字面量；
- 顺手把整个项目里所有 `!=` 用于 String 的地方 grep 一遍：`grep -rn "!= \"\|\".*!= \"" src/main/java/`。

---

### 🔴 3. `SecurityHook` 工作区边界检测可绕过（Path Traversal）（HIGH）

**位置**：`src/main/java/com/vanilla/hook/SecurityHook.java:77-89`

```java
Path target = ...; // 待校验的目标路径
if (!target.startsWith(workdir)) {
    return HookResult.deny("...");
}
```

**严重度与原因**：HIGH。`Path.startsWith(String)` 在 JDK 中做的是**字符串前缀比较**，而非路径语义比较。如果 `workdir = /home/u/proj`，那么：

- `/home/u/proj-evil/secret.txt` —— 以 `/home/u/proj` 开头 → **被误判为工作区内**，安全检查放行；
- `/home/u/proj2/file` —— 同上被放行；
- `/home/u/proj/etc/passwd` —— 通过（这其实是合法路径，但语义上"前缀相等"≠"在工作区树内"）。

这是一个**典型的 path traversal / sibling directory 绕过**，可能让 Agent 读取/修改预期工作区之外的文件。

**复现 / 影响**：

```bash
# workdir = /tmp/codey-workspace
# 创建一个相邻目录：
mkdir /tmp/codey-workspace-evil
echo "TOP SECRET" > /tmp/codey-workspace-evil/secret.txt
# 通过 Agent 读取 "../../../tmp/codey-workspace-evil/secret.txt"
# 经 Path.normalize() 后 = /tmp/codey-workspace-evil/secret.txt
# SecurityHook 的 startsWith 判定 → true → 放行 ❌
```

影响：用户预期的"工作区沙箱"形同虚设，Agent 可读/写/删任意路径。

**修复建议**：

使用 `Path` 的路径语义 + 分隔符感知比较：

```java
Path workdir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
Path target = requestedPath.toAbsolutePath().normalize();

boolean inside = target.equals(workdir)
              || target.startsWith(workdir.resolve("dummy").getParent()); // trick: workdir + "/"
```

更简洁的标准做法：

```java
boolean inside = target.toString().equals(workdir.toString())
              || target.toString().startsWith(workdir.toString() + File.separator);
```

或使用 NIO 的 `Path.startsWith`（它本身就是按 path segment 比较的，但**只对 `Path` 重载生效**）：

```java
boolean inside = target.startsWith(workdir);
// 注意：当 target 是 workdir 的子目录或相同路径时为 true
//       但这仍可能在 workdir 末尾是否带 separator 上有歧义，需配合 equals
if (!inside || !target.toAbsolutePath().normalize().startsWith(workdir.toAbsolutePath().normalize())) {
    return HookResult.deny("path outside workdir");
}
```

并加上：

- 禁止任何 workdir 是另一更长路径前缀的工作区（启动时校验）；
- 对解析后的 `target` 额外校验不存在 `..` 段（防御 symlink 之外）；
- 在 Linux 上考虑用 `chroot`/`bubblewrap`/`container` 做真沙箱，而不只是字符串比较。

---

### 🔴 4. `MemoryManager.consolidateMemories` 记忆丢失竞态（HIGH）

**位置**：`src/main/java/com/vanilla/memory/MemoryManager.java`（`consolidateMemories` 方法）

**严重度与原因**：HIGH。记忆合并的流程是：

1. 读取所有 memory 文件；
2. **删除**所有 memory 文件；
3. 调 LLM 重新生成合并后的 memory；
4. 把新结果写回文件。

第 2 步和第 4 步之间任意环节抛异常（LLM 超时、磁盘满、进程被 kill、OOM）→ **用户全部持久化记忆永久丢失**。这是一个"先销毁再重建"的反模式（destructive-then-rebuild），缺少原子性保证。

**复现 / 影响**：

```java
// 伪代码重现
for (Path f : memoryFiles) Files.delete(f);    // ← 已删除
List<Memory> merged = llmClient.consolidate(all); // ← 抛 IOException / 超时
// 后续 writeAll(merged) 永远不会执行
Files.writeString(f, merged.toJson());          // ← 用户下次启动：空 memory
```

影响：

- 用户长期积累的项目知识/偏好/历史决策**全部消失**；
- 无任何备份/恢复机制（没有 `.bak`、没有 WAL、没有重试）；
- 静默失败（无 try/catch，至少没有 fallback）→ 用户毫无感知。

**修复建议**：采用**写时复制 + 原子重命名**：

```java
public void consolidateMemories() {
    Path tempDir = memoryDir.resolve(".consolidate-" + UUID.randomUUID());
    Files.createDirectory(tempDir);
    try {
        // 1. 写到临时目录
        for (Memory m : llmConsolidated) {
            Path tmp = tempDir.resolve(m.id() + ".json.tmp");
            Path fin = tempDir.resolve(m.id() + ".json");
            Files.writeString(tmp, m.toJson());
            Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE);
        }
        // 2. 验证：至少能 parse 回来
        for (Path f : Files.list(tempDir).toArray(Path[]::new)) {
            parse(Files.readString(f));   // 任何 parse 失败 → 抛异常，不切换
        }
        // 3. 原子切换：删除旧文件，move 新文件进来
        //    或者更好：保留旧目录作为 .bak，新目录 rename 上来
        Path backup = memoryDir.resolve(".bak-" + Instant.now().toEpochMilli());
        moveDir(memoryDir, backup);            // 旧目录改名（不是删！）
        moveDir(tempDir, memoryDir);            // 新目录顶替
    } catch (Exception e) {
        // 清理临时目录，保留原 memoryDir 不动
        deleteRecursively(tempDir);
        throw new MemoryConsolidationException("merge failed, originals preserved", e);
    }
}
```

关键点：

- **绝不先删原文件** —— 始终"新数据 ready → 原子切换"；
- 整个流程任何一个步骤失败 → 用户原数据完整保留；
- 旧版本改名为 `.bak-<timestamp>` 保留 N 天，便于人工回滚。

---

### 🟠 5. `ReactiveMessageCompactor.reactiveCompact` 无限下标循环（MEDIUM）

**位置**：`src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java`（`reactiveCompact` 方法）

```java
for (int i = history.size() - 1; ; i--) {
    if (canInjectAt(history.get(i))) {
        // 注入
        break;
    }
}
```

**严重度与原因**：MEDIUM。循环**没有下界检查**（无 `i > 0` 或 `i >= 0` 终止条件）。当 `history` 中所有条目都是 `TOOL_EXECUTION_RESULT`（即没有任何"可注入"位置）时，`i` 一路减到 `-1`、`-2`...，`history.get(-1)` 抛 `IndexOutOfBoundsException`，主对话循环直接崩。

**复现 / 影响**：

```java
List<ChatMessage> history = List.of(
    toolResult("step 1"),
    toolResult("step 2"),
    toolResult("step 3"),
    toolResult("step 4"),
    toolResult("step 5")
);
compactor.reactiveCompact(history, budget);   // → IndexOutOfBoundsException
```

影响：

- Agent 进程崩溃 / 主循环未捕获 → 用户失去会话；
- 不同于 OOM，这是**确定可触发**的（构造特定 history 序列即可），攻击面虽小但稳定性隐患大。

**修复建议**：

```java
for (int i = history.size() - 1; i >= 0; i--) {
    if (canInjectAt(history.get(i))) {
        injectAt(history, i, compactionSummary);
        break;
    }
}
// 循环结束后仍未注入 → 走 fallback（强制 prepend summary）
if (!injected) {
    history.add(0, compactionSummary);
}
```

附加：

- 把 `canInjectAt` 的判定显式列出（USER / ASSISTANT 文本消息 vs TOOL_EXECUTION_RESULT），便于审计；
- 单测覆盖"全 TOOL_EXECUTION_RESULT history"的 case。

---

## 附录：其余次级风险

按严重度递减：

### A. `SecurityHook` deny-list 使用 `contains` 文本匹配（MEDIUM）

- 位置：`SecurityHook.java` 拒绝列表
- 问题：`"sudo"` 会命中 `"sudoedit"`、`"sudoers"`；`"rm "` 会命中 `"firm"`、`"program"`、`"confirm"`。
- 修复：使用**按 token / 正则 word-boundary** 匹配（`\bsudo\b`），或基于解析后的命令 AST（`shlex` 切分后逐段判断）。

### B. `MemoryManager` `Files.list()` 流未关闭（MEDIUM → 资源泄漏）

- 位置：`MemoryManager.java` 使用 `Files.list(dir).forEach(...)` 或 `.collect(...)` 但未 try-with-resources。
- 修复：包 `try (Stream<Path> s = Files.list(dir)) { ... }`。

### C. `MemoryManager.parse()` `line.split(": ")[0]` 数组越界（MEDIUM）

- 位置：解析 memory 行时。
- 问题：缺少 `": "` 分隔符时（如空行、注释行、格式损坏）→ `ArrayIndexOutOfBoundsException`。
- 修复：
  ```java
  String[] kv = line.split(": ", 2);
  if (kv.length < 2) { log.warn("skip malformed: {}", line); continue; }
  ```

### D. `MemoryManager.injectRelevantMemory` `Integer::valueOf` 数字格式异常（MEDIUM）

- 位置：LLM 返回 score/limit 字段时。
- 问题：LLM 偶尔返回 `"five"`、`"N/A"` 等 → `NumberFormatException` 直接崩主循环。
- 修复：`Integer.parseInt(s, 10)` 包 try/catch，失败 fallback 到默认值。

### E. `Codey.java` 字段初始化器读环境变量（MEDIUM）

- 位置：`Codey.java` 的 `OPENAI_API_KEY` / `BASE_URL` / `MODEL_NAME` 字段初始化阶段。
- 问题：环境变量缺失时启动失败（或在某些 CI 环境被设为 `null` 后下游 NPE）。
- 修复：字段保持 `null`/空，由 `init()` 阶段集中校验并给出友好错误。

### F. `SystemMessageBuilder.lastContextKey` / `lastPrompt` 静态可变缓存（MEDIUM → 并发串扰）

- 位置：`prompt/SystemMessageBuilder.java`。
- 问题：在子 Agent 多线程场景下，**多个 Agent 共享同一静态字段** → cache 命中错误的 key、prompt 串味。
- 修复：缓存移到实例字段或 `ThreadLocal`，或干脆禁用缓存（每次重新构造）。

### G. `ConsoleRenderer.printMessageState` 空 history 触发 `NoSuchElementException`（MEDIUM）

- 位置：`util/ConsoleRenderer.java` `.max(...).getAsInt()`。
- 问题：history 为空（首轮渲染、或者 compact 后清空）→ `OptionalInt.empty().getAsInt()` 抛 `NoSuchElementException`。
- 修复：
  ```java
  int max = history.stream().mapToInt(m -> m.text().length()).max().orElse(0);
  ```

### H. `BudgetMessageCompactor` TOCTOU（MEDIUM）

- 位置：`BudgetMessageCompactor.java` 的 `Files.exists(f) ? ... : Files.writeString(f, ...)`。
- 问题：检查与写入之间被并发写 → 数据丢失。
- 修复：直接 `Files.writeString(f, ..., CREATE, WRITE, TRUNCATE_EXISTING)`，用 NIO 原子标志代替 if。

### I. `TodoWriteTool.execute` 解析 `args.getStr("todos")` 的类型问题（LOW）

- 位置：`tool/TodoWriteTool.java`。
- 问题：LLM 可能把 `todos` 字段返回为对象数组（正确）或字符串化的 JSON（也常见），`getStr` + `JSONUtil.toList(String, Class)` 在前者情形失败。
- 修复：根据 `args.getType("todos")` 分支处理（`JSONArray` → 直接转；`String` → parse；其它 → 报错并把原值回传）。

### J. `ChatMessageJsonConvertor.convert` 静默返回 null（LOW）

- 位置：`util/ChatMessageJsonConvertor.java`。
- 问题：序列化失败时返回 `null`，上游若不判空则 NPE；错误被吞掉。
- 修复：抛 `JsonConversionException` 或至少 `log.error(...)`。

---

## 测试覆盖空缺（建议）

当前测试目录：

```
src/test/java/com/vanilla/AppTest.java
```

内容**仅为一个 JUnit 4 桩测试**：

```java
public class AppTest {
    @Test public void testApp() {
        assertTrue(true);
    }
}
```

**实际业务代码覆盖率为 0%**。建议（按 ROI 排序）补齐：

1. **`ToolManager.subagentToolSpecifications`** —— 单测覆盖 `new String("task")` 字符串不驻留场景（验证 Top5 #2 修复）。
2. **`SecurityHook` 路径校验** —— 用 `/workdir-evil`、`/workdir2`、`..` 构造 negative cases（验证 Top5 #3）。
3. **`MemoryManager.consolidateMemories` 异常路径** —— mock LLM 抛异常 → 验证原文件未被删除。
4. **`ReactiveMessageCompactor.reactiveCompact`** —— 全 `TOOL_EXECUTION_RESULT` 输入 → 不抛异常且返回 fallback（验证 Top5 #5）。
5. **`MemoryManager.parse`** —— malformed line / 空行 / 缺分隔符。
6. **`ConsoleRenderer.printMessageState`** —— 空 history。
7. **`ChatMessageJsonConvertor.convert`** —— 非法 JSON 输入 → 抛特定异常而非 null。

工具链建议：

- 引入 **JUnit 5** + **AssertJ** + **Mockito**；
- 启用 **JaCoCo** 覆盖率门禁（建议起步 ≥ 60% line coverage on `core/`）；
- CI 跑 `mvn verify`，禁止 `assertTrue(true)` 之类的空测试。

---

## 优先级建议（落地顺序）

| 优先级 | 项 | 预估工作量 |
|---|---|---|
| **P0 - 立即** | #1 轮换 key + 从历史清除 | 0.5h（轮换）+ 1h（清历史） |
| **P0 - 立即** | #2 `!=` 改为 `equals` | 5 min |
| **P0 - 立即** | #3 路径边界改 Path 比较 | 30 min + 单测 |
| **P1 - 本周** | #4 `consolidateMemories` 原子化 | 2h + 单测 |
| **P1 - 本周** | #5 `ReactiveMessageCompactor` 下界 | 15 min + 单测 |
| **P2 - 下迭代** | 附录 A–J 全部修复 | 各 30 min – 2h |
| **P2 - 下迭代** | 补齐测试覆盖至 ≥ 60% | 2–3 人日 |

---

**报告结束**。如需对任意条目提供完整 patch / 单元测试模板，请告知具体编号。