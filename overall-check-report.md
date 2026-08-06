# 项目整体检查报告

> 范围：`com.vanilla` 包下 38 个 Java 文件 / 3 339 行 / 0 个真实测试（仅 Maven 脚手架 AppTest）
> 时间：在 JDK 17 语法审计（`jdk-syntax-report.md`）+ Top5 安全/正确性审计（`severe-problems-report.md`）+ 5 个压缩器介绍（`compactors-intro.md`）基础上做的**整体横向体检**
> 优先级：🔴 必修 / 🟡 建议 / 🟢 良好 / ⚪ 信息

---

## 一、整体架构鸟瞰

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Codey.java              ← 入口、REPL 主循环、用户交互                     │
├──────────────┬──────────────┬─────────────────┬────────────────────────┤
│   tool/      │  compactor/  │   memory/       │     hook/              │
│  10 个工具    │  5 个压缩器   │   MemoryMgr     │ HookDispatcher         │
│  (Shell、FS) │  (阈值/LLM)  │   (CRUD)        │ SecurityHook           │
├──────────────┼──────────────┼─────────────────┼────────────────────────┤
│   skill/     │   prompt/    │    content/     │     util/              │
│  SkillManager│ SystemMessage│   Prompt 提示   │ ConsoleRenderer        │
│              │ Builder      │                 │ ChatMessageJsonConvertor│
└──────────────┴──────────────┴─────────────────┴────────────────────────┘
```

**架构评价**：分层清晰、职责单一、无循环依赖。`util`/`hook` 是底层能力，`tool`/`compactor`/`memory`/`skill` 是中层能力，`Codey` 是顶层编排。没有 Spring/Guice/反射注入——纯手工 new，符合小型 agent 的可控性偏好。🟢

---

## 二、各项维度评分

| 维度 | 评分 | 备注 |
|---|---|---|
| **JDK 17 现代化** | 🟢 A | text block / record / instanceof 模式匹配 / switch 表达式 / `Files.readString` 全部正确使用（详见 `jdk-syntax-report.md`） |
| **依赖最小化** | 🟢 A | 仅 `langchain4j` + `jline` + `jackson` + `lombok`；未引入 Spring/Guava/Apache Commons 等"重力"依赖 |
| **错误处理** | 🟡 C+ | 见 §三 |
| **并发安全** | 🔴 C- | 见 §四 |
| **资源管理** | 🟡 B- | 见 §五 |
| **输入校验 / 路径安全** | 🟡 B | SecurityHook 存在路径前缀绕过（Top5 #3） |
| **测试覆盖** | 🔴 F | 仅 1 个 `assertTrue(true)`，无任何实际测试 |
| **可观测性** | 🟡 D+ | 仅 `System.out.println`，无结构化日志、无 trace ID |
| **配置管理** | 🟡 C | 见 §七 |
| **文档** | 🟢 A- | 三份审计 + 本报告，README 缺失 |

---

## 三、错误处理（🟡 C+）

**38 个文件里出现 53 处 `catch`**，模式分析如下：

| 模式 | 出现次数 | 评价 |
|---|---|---|
| `catch (X e) { /* 空块或打印 */ }` | 显著 | 🟡 部分吞掉异常（如 `Codey.java:112` `IOException ignored`、`BashTool.java:211` `NumberFormatException ignored`、`SkillManager.java:34`） |
| `catch (X e) { System.out.println("[xxx] " + e.getMessage()); }` | 主要 | 🟡 把 stack trace 折成单行 print，无 logger、无等级、无 trace |
| `catch (X e) { throw new RuntimeException(...) }` | 少数 | 🟢 合理包装 |
| `catch (X e) { throw new UncheckedIOException(...) }` | 1 处 | 🟢 规范 |

**🔴 必修问题**：
1. **`SkillManager.java:34`** — `catch (IOException e) { ... }` 中断整个目录遍历，单个坏文件会让**所有** skill 失效。需要降级为"跳过坏文件继续遍历"。
2. **`Codey.java:112`** — `IOException ignored` 直接吞，可能掩盖 REPL 关键失败。

---

## 四、并发安全（🔴 C-）

**结论**：项目**没有**任何线程同步原语。`grep -n "synchronized\|AtomicReference\|volatile\|ConcurrentHashMap"` 返回空。

| 风险点 | 位置 | 触发条件 | 后果 |
|---|---|---|---|
| 多压缩器并发调 `history.clear()` / `history.add()` | `LLMMessageCompactor`/`Snip`/`Mico`/`Reactive` | 若 REPL 与后台线程同时触发 | `ConcurrentModificationException` 或内容撕裂 |
| 多压缩器并发 `Files.writeString` 同一 `toolUseId` | `BudgetMessageCompactor` | 同上 | 文件互相覆盖（TOCTOU，Top5 #5 之一） |
| `ToolManager` 持有可变工具列表 | `ToolManager.java` | 注册新工具 | 读侧需 `CopyOnWriteArrayList` 或不可变快照 |
| `MemoryManager.consolidateMemories` 先删后写 | `MemoryManager.java` | 并发读 | 读侧 `Files.notExists` 后窗口期文件消失（Top5 #4） |
| `MemoryWrapper` JSON 反序列化的 List 是可变引用 | `MemoryManager.java` | 反序列化后被外部修改 | 污染内存数据 |

**🔴 必修**：所有压缩器入口必须先**复制 defensive copy**（`new ArrayList<>(history)`），再就地修改；若多线程触发，加 `synchronized(history) { ... }` 或用 `CopyOnWriteArrayList`。

---

## 五、资源管理（🟡 B-）

| 资源类型 | 使用情况 | 评价 |
|---|---|---|
| `Scanner` / `BufferedReader` / `BufferedWriter` | 仅 1 处 `Scanner` 在 `Codey.java` | 🟢 |
| `Process` / `ProcessBuilder` | `BashTool` 用 `ExecutorService` 提交 `Process` | 🟡 未限制并发进程数（恶意 user 可同时开 1000 个 shell）；`waitFor(timeout)` 但未 `destroyForcibly` 兜底 |
| `HttpClient` | `langchain4j` 内部 | 🟡 不在项目控制范围 |
| 文件流 | `Files.readString` / `Files.writeString` | 🟢 NIO 短路径，自动关闭 |
| 线程池 | `BashTool` 自建 `ExecutorService` | 🟡 **未 shutdown**（`Codey.java` 进程退出时线程池不收尾） |

**🟡 建议**：`BashTool` 的 `ExecutorService` 需在 `Codey` 的 `addShutdownHook` 中 `shutdownNow()`。

---

## 六、工具子系统横向对比

| 工具 | 文件 | 行数 | 路径校验 | 错误处理 | 并发安全 | 总评 |
|---|---|---|---|---|---|---|
| Bash | BashTool.java | 295 | 🟢（注入黑名单） | 🟡 | 🟡 | 🟡 |
| EditFile | EditFileTool.java | 150 | 🟢（经 Hook） | 🟡 | 🟢 | 🟢 |
| WriteFile | WriteFileTool.java | 104 | 🟢 | 🟡 | 🟢 | 🟢 |
| ReadFile | ReadFileTool.java | 95 | 🟢 | 🟡 | 🟢 | 🟢 |
| Glob | GlobTool.java | 150 | 🟢 | 🟡 | 🟢 | 🟢 |
| TodoWrite | TodoWriteTool.java | 74 | — | 🟡 | 🟢 | 🟢 |
| LoadSkill | LoadSkillTool.java | 32 | — | 🟡 | 🟢 | 🟢 |
| SpawnSubagent | SpawnSubagentTool.java | 186 | — | 🔴（无限重试） | 🟢 | 🔴 |

**🔴 必修**：`SpawnSubagentTool.java`（已修复 env vars）仍有**递归死循环风险**——若子 agent 返回的内容触发了它再次调用自己，会形成无限递归子任务。需要在 `Codey` 主循环里加最大子任务嵌套深度（建议 `MAX_SUBAGENT_DEPTH=3`）。

---

## 七、配置管理（🟡 C）

| 来源 | 出现位置 | 评价 |
|---|---|---|
| 环境变量 | `SpawnSubagentTool`（env vars） | 🟢 已修 |
| 系统属性 `-Dxxx=yyy` | 未发现 | 🟡 缺失，敏感参数无法热加载 |
| 配置文件 `application.yml` / `config.properties` | 未发现 | 🟡 缺失 |
| 硬编码常量 | `BudgetMessageCompactor`、`LLMMessageCompactor` 等到处散布 | 🔴 阈值（30k/200k/50k/60/120/5/2/58）全是 magic number，无 Config 类 |
| `Map.of(...)` 嵌入路径 | `SkillManager` 用 `Map.of("Skills", "...")` | 🟡 路径硬编码，跨平台需重写 |

**🟡 建议**：抽 `Config.java`（或 `application.yml`）统一管阈值、路径、超时。

---

## 八、可观测性（🟡 D+）

- **日志**：`System.out.println("[compactor] ...")` + `System.out.flush()` 散布在 9 个文件里
- **缺**：❌ SLF4J/Logback、❌ 日志级别（DEBUG/INFO/ERROR）、❌ 时间戳、❌ trace ID、❌ 结构化字段
- **后果**：生产环境出问题时只能用 print 凑活；`Mico` 压缩后大量历史被替换，无法回溯"哪条消息被压缩了"

**🟡 建议**：引入 SLF4J + Logback，保留 `[name]` 前缀做向后兼容。

---

## 九、测试覆盖（🔴 F）

- `src/test/java/com/vanilla/AppTest.java`：仅 1 个 `assertTrue(true)`（Maven 脚手架）
- **0 个** 压缩器测试、**0 个** Hook 测试、**0 个** Tool 测试、**0 个** Memory 测试

**Top5 #1-#5 中至少 3 个**（路径绕过、Memory 先删后写、Reactive OOB）单测能在 5 行内直接复现。详见 `severe-problems-report.md` §测试覆盖空缺。

---

## 十、未在前两份报告覆盖的"中等问题"汇总

| 编号 | 位置 | 问题 | 优先级 |
|---|---|---|---|
| **#N1** | `SkillManager.java:34` | 单个坏文件让所有 skill 失效 | 🟡 |
| **#N2** | `BashTool.java` 线程池 | 未 shutdown，进程退出时残留线程 | 🟡 |
| **#N3** | `SpawnSubagentTool.java` | 无递归深度限制，子 agent 可无限嵌套 | 🔴 |
| **#N4** | 全部压缩器 | history 无 defensive copy，并发触发风险 | 🔴 |
| **#N5** | `Codey.java:112` | `IOException ignored` 吞关键异常 | 🟡 |
| **#N6** | `BashTool.java` 进程数 | 无并发上限，可被恶意 prompt 打爆 | 🟡 |
| **#N7** | 全项目 | magic number 散布，无 Config 统一管理 | 🟡 |
| **#N8** | 全项目 | 无 SLF4J，System.out 散落 | 🟡 |
| **#N9** | 全项目 | 无 README、使用文档、架构图 | 🟢 |
| **#N10** | 全项目 | 无 CI（无 `.github/workflows` / `.gitlab-ci.yml`） | 🟡 |

---

## 十一、Top10 必修清单（合并前报告 + 本报告）

| 序 | 问题 | 来源 | 预估工时 |
|---|---|---|---|
| 1 | 明文 API key 仍在 git 历史（仅删源码无效）| `severe-problems-report.md` #1 | 用户手动轮换 + `git filter-repo` |
| 2 | `SecurityHook` 路径前缀绕过（`/workdir-evil`）| Top5 #3 | 1h |
| 3 | `MemoryManager.consolidateMemories` 先删后写 | Top5 #4 | 2h |
| 4 | `ReactiveMessageCompactor` 无限下标 OOB | Top5 #5 | 30min |
| 5 | `BudgetMessageCompactor` TOCTOU | 本报告 #N4 子项 | 30min |
| 6 | **N3** `SpawnSubagentTool` 无递归深度限制 | 本报告 #N3 | 1h |
| 7 | **N4** 全压缩器 history 无 defensive copy | 本报告 #N4 | 2h |
| 8 | **N1** `SkillManager` 单文件阻断遍历 | 本报告 #N1 | 30min |
| 9 | **N2** `BashTool` 线程池未 shutdown | 本报告 #N2 | 15min |
| 10 | **测试**：补 Budget/SecurityHook/Memory/Reactive 单测 | `severe-problems-report.md` 测试空缺 | 4h |

---

## 十二、亮点（值得保留的设计）

✅ **JDK 17 现代化使用**：`record`、`instanceof` 模式匹配、switch 表达式、text block、`Files.readString`、`Path.of`、`Map.of` 全部用对，没有降级兼容性包袱
✅ **分层清晰**：util → hook → tool/compactor/memory/skill → Codey，单向依赖
✅ **压缩器分层**：阈值类（快）+ LLM 类（语义无损），互为兜底
✅ **Hook 机制可扩展**：`Hook`/`HookContext`/`HookDispatcher`/`HookRegistery`/`HookResult` 五件套，自定义 `SecurityHook` 注入拦截
✅ **JSON 序列化兜底**：压缩器统一走 `ChatMessageJsonConvertor` 而非 `text()`，多模态安全
✅ **失败兜底**：`LLMMessageCompactor` 在 LLM 失败时保留原 history，不丢上下文
✅ **预算落盘**：超长 tool_result 自动落盘 + 引用，模型上下文中保留可回溯入口

---

## 结论

**项目状态**：能跑、能压缩、能调 LLM、能拦截危险命令——MVP 已具备。但**离生产可用还有 1-2 周修补距离**，主要集中在并发、错误处理、测试三块。

**优先行动**：
1. 用户**立即去 minimaxi 控制台轮换 API key**（明文仍在 git 历史）
2. 修 Top5 #3-#5（路径 / Memory 原子化 / Reactive 下界）—— 4h
3. 修本报告 #N3（子 agent 深度）—— 1h
4. 补核心单测 —— 4h

要我按这个顺序推进吗？或者你想先看哪一块的具体修复方案？