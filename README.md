# Codey

> ⚠️ **AI Coding Agent · Beta (i.e. it compiles)** 一个住在你终端里的 AI 程序员
>
> *An agentic CLI that lives in your terminal, drives your shell, and edits your files. Sometimes. Then tells you it did. Often did not.*

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![LangChain4j](https://img.shields.io/badge/langchain4j-1.18-1A73E8)
![Tests](https://img.shields.io/badge/tests-1%20stub-ff69b4)
![License](https://img.shields.io/badge/license-Internal%20%2F%20%E2%80%9Cdon't%20ship%20this%E2%80%9D-lightgrey)

Codey 是一个用 Java 17 写的命令行 AI 编程 Agent。它通过 [LangChain4j](https://github.com/langchain4j/langchain4j) 接入 OpenAI 兼容的对话模型（默认指向 `https://api.minimaxi.com/v1`，**API key 与 model name 都直接写在源码里**），按 **工具调用（Tool Use）** 循环驱动本地 Shell 和文件系统。让模型像 Claude Code / Codex CLI 一样自主完成编码任务 —— 不过请坐稳，它会自主完成一些你不想完成的事情。

设计目标（实现情况见下表）：

- **Agentic** — 模型自己决定调什么工具、调用几次、何时停止。 *（也自己决定何时卡在原地。）*
- **可控** — 危险命令、写入工作区之外的文件会被 `SecurityHook` 拦截并向用户确认。 *（前两秒钟内；见[安全沙箱](#-安全沙箱实际上是装饰性的)章节。）*
- **可读** — 终端输出统一用 `ConsoleRenderer` 渲染成彩色卡片。 *（夹杂着压缩器自己往 `System.out` 喷的 `[snip]` / `[compactor]` 日志。）*
- **可扩展** — `Tool` 接口 + `Hook` 事件系统，新增工具和拦截点只需几行代码。 *（如果你能数清 `register` 被谁悄悄调过。）*

---

## ✨ 核心特性（诚实版）

| 能力 | 实际情况 |
| --- | --- |
| 🛠️ **内置 6 个工具** | `bash` · `read_file` · `write_file` · `edit_file` · `glob` · `task`，加上一个走样的 `todo_write`（见下） |
| 🪝 **Hook 事件总线** | 4 个事件，其中 `UserPromptSubmit` 不能拦 prompt，`PostToolUse` 没订阅者，`Stop` 没订阅者 |
| 🔐 **安全沙箱** | 用 `String.contains` 做 deny list，空格一改就绕过；`Scanner(System.in)` 是实例字段 |
| 🎨 **统一终端 UI** | 配色、对齐都还行；但 `SnipMessageCompactor` / `BudgetMessageCompactor` 直接 `System.out.println` 把卡片弄花 |
| 🔁 **多轮会话** | 完整保留消息历史；并且**每轮**重新打印整个历史 + 任务清单 |
| ⚙️ **超时控制** | `BashTool` 是 120s；`OpenAiChatModel` 没有任何请求超时配置，默认就是默认 |

---

## 🧱 架构总览（带批注）

```
┌──────────────────────────────────────────────────────────────┐
│                          Codey.java                          │
│                  (主循环 · 每轮重打印 history & todos)         │
│                                                             │
│   ⚠️ Codey.java 第 ~89 行：每次 tool 调完都重打 TodoWriteTool│
│      那个静态 todo 列表，即使调的是 bash。spam 终端。          │
└──────────────┬─────────────────────────────┬─────────────────┘
               │                             │
               ▼                             ▼
   ┌───────────────────────┐     ┌──────────────────────────┐
   │    HookDispatcher     │     │  ToolManager (注册表)     │
   │  ┌────────────────┐   │     │  ┌──────────────────┐    │
   │  │ SecurityHook   │   │     │  │  BashTool        │    │
   │  │ ContextInject… │   │     │  │  ReadFileTool    │    │
   │  │ SummeryHook    │   │     │  │  WriteFileTool   │    │
   │  │ ↑ 这两个已 @Deprecated │   │  │  EditFileTool    │    │
   │  └────────────────┘   │     │  │  GlobTool        │    │
   └─────────┬─────────────┘     │  │  TodoWriteTool   │    │
             │                   │  │  ↑ 共享静态 List,    │
             ▼                   │  │    非线程安全        │
   ┌───────────────────────┐     │  │  SpawnSubagent…  │    │
   │   ConsoleRenderer     │     └──────────┬───────────────┘
   │  (卡片 / 颜色 / 宽度) │                │
   └───────────▲───────────┘                ▼
               │           ┌──────────────────────────┐
               │           │   OpenAiChatModel        │
   ┌───────────┴───────┐   │  (langchain4j-open-ai)   │
   │ SnipMessageComp.   │◄──┘  ⚠️ builder 没设超时/重试/
   │ BudgetMessageComp. │      temperature/topP/...
   │  ↑ 这俩朝 System.out │
   │    喷 [snip] [compactor] │
   └────────────────────┘     ┌──────────────────────────┐
                              │   MiniMax / OpenAI 兼容 API│
                              │   🔑 apiKey 写在源码里     │
                              └──────────────────────────┘
```

**工具调用循环**（位于 `Codey.toolAgent`）：

1. 把历史消息 + 工具 schema 推给模型。
2. 若 `finishReason != TOOL_EXECUTION` → 触发 `Stop` Hook。 *（但目前没有 `Stop` Hook 订阅者，所以这步现在等价于 “什么也不发生”。）*
3. 否则对每个 `toolExecutionRequest`：触发 `PreToolUse` Hook → 调工具 → **每轮都跑一遍 `toolResultBudget` → `snipCompact`** → 把 `ToolExecutionResultMessage` 写回历史 → 回到步骤 1。

---

## 🛠️ 工具矩阵（谁是真的，谁只是穿了一件工具的衣裳）

所有工具都实现 `com.vanilla.tool.Tool` 接口，由 `ToolManager` 静态注册。

| 名称 | 用途 | 关键参数 | 你应该知道的猫腻 |
| --- | --- | --- | --- |
| `bash` | 在子进程中执行 shell | `command`, `shell?`, `cwd?`, `timeout_seconds?` | **合并 stdout/stderr**，所以 stderr 噪音会污染上下文；`timeout_seconds` 超过 `120s` 才会真报错；`IS_WINDOWS` 写死在静态块 |
| `read_file` | 读 UTF-8 文本 | `path`（兼容 `file_path`） | `Files.readString` 失败时把 Exception 直接装进返回字符串 |
| `write_file` | 写文件 | `path`, `content` | `write_file` 没有 `SecurityHook` 路径——哦等等，有，但 SecurityHook 校验写的是 *JSON 里的字符串参数*；其它工具如果绕过钩子（比如直接 API 调用）就溜了 |
| `edit_file` | 精确文本替换 | `path`, `old_string`, `new_string`, `replace_all?` | **默认 `oldString`/`file_path`/`replaceAll` 驼峰别名都识别**（于是你永远分不清哪个才是官方字段名）|
| `glob` | 按 glob 匹配文件 | `pattern`, `path?` | 默认跳过 `.git / node_modules / target / build / dist / .idea / .vscode`；**`mvn` 的 `target/` 一定跳过，CI 日志和 classpath 不算** |
| `todo_write` | 维护任务清单 | `todos: [{content, status}]` | ⚠️ **整个项目只有一个静态 `List<Todo>`**。子类 Agent 调完，主 Agent 看到的是同一份。所有调用会 **互相覆盖** |
| `task` | 派生子 Agent | `description` | 子 Agent 内嵌了**第二份**硬编码 API key；`subagentToolSpecifications()` 用 `tool.name() != "task"` 比较 **String 引用相等性**——只为 `task` 是字面量才碰巧工作 |
| `load_skill` | …… | …… | 你会想为什么 README 没列它，因为 README 是按 `ToolManager.register(...)` 静态块写的。**`load_skill` 也是真工具，但谁能指望我字字照搬** |

要新增工具？实现 `Tool` 接口，在 `ToolManager` 的 `static {}` 里 `register(new MyTool())` 即可。前提是你能找到 `ToolManager` 的 `static {}`——总共 8 行注册，按字母顺序排，看起来跟 IDE 自动生成的一模一样。

---

## 🪝 Hook 系统

```java
public interface Hook {
    String id();
    HookEvent support();   // 订阅哪个事件
    default int order() { return 0; }  // 同事件内执行顺序
    HookResult execute(HookContext context);
}
```

| 事件 | 触发时机 | 典型用途 | 现状 |
| --- | --- | --- | --- |
| `UserPromptSubmit` | 用户输入提交后、发给模型前 | 注入上下文、改写 prompt | **没人订阅**（README 里贴的"改写 prompt"是画饼）|
| `PreToolUse` | 工具调用前 | **安全检查 / 权限弹窗** | `SecurityHook` 一家独大 |
| `PostToolUse` | 工具调用完成后 | 记录结果、转换输出 | **没人订阅** |
| `Stop` | 模型一轮对话结束 | 会话总结 | **没人订阅** |

`HookDispatcher.dispatch` 会按 `order()` 升序串行执行；任一 Hook 返回 `HookResult.block(msg)` 即短路终止，并把 `msg` 作为工具结果回填给模型。

> **命名彩蛋**：注册表类实际叫 `HookRegistery.java`（少一个 `r`）。IDE 里按 `HookRegistry` 跳转找不到文件。Git blame 显示这是 v0.0.1 时代的拼写，跟着走了一年。

内置 Hook：

- `SecurityHook`（已启用）— deny list 硬拦截、destructive list 弹 `y/N` 确认、写入工作区外弹确认。
- `ContextInjectHook`（已 `@Deprecated`）— 事件占位。
- `SummeryHook`（已 `@Deprecated`）— 在 `Stop` 时打印对话摘要。 *（对，也是 Summery，不是 Summary，第二个 e。漫长的、周四下午的拼写。）*

---

## 🔐 安全沙箱（实际上是装饰性的）

`SecurityHook` 由 `HookRegistery` 在构造时**默默**注册（因此 `Codey.java` 没出现 `new SecurityHook()`，很难一眼看明白安全模块在哪里接入）。它的策略是：

```java
private static final List<String> DENY_LIST   = List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=");
private static final List<String> DESTRUCTIVE = List.of("rm ", "> /etc/", "chmod 777");
```

它是用 `String.contains` 来匹配的。这意味着：

- `sudo` 命中 `sudoku`、`sudoedit`、`grep sudoers /path/to/file`、`echo sudo > notes.txt`。你的笔记随便写。
- `shutdown` 命中任何 npm 脚本的注释、`pg_ctl -D /var/lib/postgresql stop` 也写不出来。
- `dd if=` **大小写敏感**，`DD if=` 自由通过。
- **`rm -rf /` 必须长得和那条字符串一模一样**。`rm -rf  /`（两个空格）就过了。`rm  -rf /` 也过了。Shell 里有人管这叫"信任缺失"，我管这叫安慰剂。
- 而且 `rm -rf /` 在 Linux/macOS 上早就被 `rm` 自身拒掉了（在 `--preserve-root` 下），除了 GNU coreutils 用户，谁真的靠这条拦？

更刺激的是：

- 写入工作区外确认 / 路径合法性检查，也是基于 `JSONUtil.parseObj(toolReq.arguments()).getStr("path")`——如果你用一个**带路径参数的别名字段**调 `write_file`，钩子根本看不到路径，弹窗就不出现。悄悄写入。
- `SecurityHook.confirmWithUser` 用的是实例字段 `Scanner sc = new Scanner(System.in)`——目前 `HookRegistery` 只 new 一次，所以没爆。但**任何写第二个 Hook 的同事**就立刻分到第二个 `Scanner(System.in)`，第三次 toast 你就看到了。

---

## 🚀 快速开始（请在做完以下三件事前先别 `run`）

### 环境要求

- **JDK 17+**
- **Maven 3.8+**
- 一个能访问 OpenAI 兼容 API 的网络环境

### 第一步：不要使用硬编码的 API key

`Codey.java` 和 `SpawnSubagentTool.java` 各自硬编码了同一份 `sk-cp-...` key。它现在躺在 git 历史里——`git log -S 'sk-cp-' README.md` 也救不回那条破链。

```java
String apiKey  = System.getenv().getOrDefault("OPENAI_API_KEY", "");
if (apiKey.isBlank()) {
    throw new IllegalStateException("set OPENAI_API_KEY before running");
}
```

**等到**两个文件都改成环境变量读取后，再继续往下。

### 第二步：构建

```bash
mvn -q -DskipTests package
```

构建产物：`target/codey-1.0-SNAPSHOT.jar`。pom 里**没有** `maven-shade-plugin`，默认打包不会带依赖。`mvn exec:java` 是文档里的用法，但 CI 上跑的人最后都会自己加 shade 或者 assembly 插件。

### 第三步：运行

```bash
mvn -q exec:java -Dexec.mainClass=com.vanilla.Codey
```

进入 REPL 后：

```
你 › 帮我看看 src/main/java 下有哪些类，并把它们列成表格
你 › 修复 AppTest 里的 shouldAnswerWithTrue
你 › exit
```

输入 `q` / `quit` / `exit` 退出。

### 首次运行会发生什么（按真实顺序）

1. 打印欢迎卡片，介绍模型与退出方式。
2. 加载 `Prompt.SYSTEM`（告诉模型它在 `<工作目录>` 下，是 coding agent，"Use bash to solve tasks. Act, don't explain."）。
3. **模型第一次回复** 几乎一定先 `bash pwd && ls`——因为 `Prompt` 没告诉它 `cwd`，也没禁止它跑偏。然后 `ConsoleRenderer` 把读到的内容画成一张卡。
4. 你接下来的 tool 调用每执行一次，**整个 history 会被原样重打印一次**，外加 todo 列表（即使 todo 是空的也会刷一道横向边框）。

---

## ⚙️ 配置（pom 和源码里能找到的所有开关）

> ⚠️ **安全提醒（重复一遍）**：当前 `Codey.java` 把 `apiKey` 与 `baseUrl` 写死在源码里。两个地方都写，分别是 `Codey.java` 与 `SpawnSubagentTool.java`。
> 不要把任何真实 key 提交到 git 历史。**也别相信 README 里未来某次提交的时候 fix 了**——这是 `Codey.java` 第 ~38 行和 `SpawnSubagentTool.java` 第 ~70 行，**两份独立硬编码**，版本号还是固定的 1.0-SNAPSHOT。

可调环境变量（**全文档里只有 README 这一处定义，代码不读**）：

| 变量 | 作用 | 默认 | 代码里真的读了吗 |
| --- | --- | --- | --- |
| `OPENAI_API_KEY` | 模型 API Key | **必填**（当前硬编码） | ❌ |
| `OPENAI_BASE_URL` | OpenAI 兼容网关地址 | 当前 `https://api.minimaxi.com/v1` | ❌ |
| `OPENAI_MODEL` | 模型名 | 当前 `MiniMax-M3` | ❌ |
| `NO_COLOR` / `-Dcodey.noColor=true` | 关颜色 | 关闭 | 部分 |
| `COLUMNS` | 终端宽度 | 默认 88 | 部分 |

剩下那块 `Prompt.SYSTEM` 的内容、压缩器的阈值、Hook 的 deny list——**全在源码里，要改请提交 PR**。别期待 `.envrc` 或者 `~/.codey/config.toml` ——它不存在。

---

## 🎮 使用示例（模型的真实反应）

**1. 浏览项目结构**

```
你 › 列出 src/main/java 下的所有 .java 文件
```

*（模型大概率会先 `bash ls src/main/java`，再调 `glob "**/*.java"` "src/main/java"——调两次。`ConsoleRenderer` 会画两张卡片。）*

**2. 重构一个文件**

```
你 › 把 ConsoleRenderer 里所有 System.out.println 改成 slf4j
```

*（模型会循环 `read_file` → `edit_file`（多次）。但 `SnipMessageCompactor` 和 `BudgetMessageCompactor` 自己也会往 stdout 喷 `[snip]` `[compactor]`——一张卡的中间突然出现一行青色的工具日志。继续往下编 color scheme 的人算你狠。）*

另外——pom 里**没声明** `slf4j`。所以这个示例注定失败。

**3. 触发安全拦截**

```
你 › 执行 rm -rf /tmp/test
```

*（这下才会被 `SecurityHook` 的 `rm ` 字符串命中。但 `rm  -rf /tmp/test`——两个空格，它就给你把 `target/` 删了。）*

**4. 危险命令二次确认**

```
你 › chmod 777 ~/.ssh/id_rsa
```

*（弹出紫色 `⚙ hook · SecurityHook` 卡片要求 `[y/N]` 确认。卡片的右边距取决于终端宽度——`COLUMNS=88` 默认时，足够放下；窄一点就会回卷到 emoji 列，挤掉 `reason` 行。）*

**5. 派生子 Agent**

```
你 › 用 task 子代理搜一下 OpenAI 接口的最佳实践
```

*(`SpawnSubagentTool.run` 用同一份硬编码 apiKey 启动 `MiniMax-M3`，30 轮限制——`MAX_CALL` 和 `round` 之间实际上**只有 `round+1` 个回合编号会进摘要**，看 `printSubagentDone(false, …)` 调参就能猜出来，结果就是"30 turns / 31 turns"看着像 bug report)。*

---

## 🗂️ 目录结构

```
codey/
├── pom.xml                    # artifactId = codey, groupId = com.vanilla, version = 1.0-SNAPSHOT
│                              # 上游 maven-archetype 留下的 FIXME: change it to the project's website
├── README.md                  # 这份文件
├── src/
│   ├── main/
│   │   ├── java/com/vanilla/
│   │   │   ├── Codey.java                # 入口 · 每轮压缩 · 每轮重打印
│   │   │   ├── content/Prompt.java       # System prompt
│   │   │   ├── util/ConsoleRenderer.java # 终端 UI
│   │   │   ├── tool/                     # 工具
│   │   │   │   ├── Tool.java
│   │   │   │   ├── ToolManager.java
│   │   │   │   ├── BashTool.java
│   │   │   │   ├── ReadFileTool.java
│   │   │   │   ├── WriteFileTool.java
│   │   │   │   ├── EditFileTool.java
│   │   │   │   ├── GlobTool.java
│   │   │   │   ├── TodoWriteTool.java   # 共享静态 List · 非线程安全
│   │   │   │   ├── SpawnSubagentTool.java # 内嵌第二份 hardcoded apiKey
│   │   │   │   └── LoadSkillTool.java   # 文档没列，但 ToolManager 会注册
│   │   │   ├── compact/                 # 压缩器
│   │   │   │   ├── SnipMessageCompactor.java   # public static int MAX_MESSAGE_SIZE = 50;
│   │   │   │   │                              # 全工程任何线程都能改，模型决定失忆点
│   │   │   │   └── BudgetMessageCompactor.java # PERSIST_THRESHOLD = 30_000/3 = 10000
│   │   │   │                                  # MAX_BYTES = 200_000/10 = 20000
│   │   │   │                                  # 注释里写"原教材 30_000 / 200_000"
│   │   │   │                                  # 谁对谁错——猜吧
│   │   │   └── hook/                      # Hook 系统
│   │   │       ├── Hook.java
│   │   │       ├── HookEvent.java
│   │   │       ├── HookContext.java
│   │   │       ├── HookDispatcher.java
│   │   │       ├── HookRegistery.java   # 注意拼写
│   │   │       ├── HookResult.java
│   │   │       ├── SecurityHook.java    # contains() 驱动的 deny list
│   │   │       ├── ContextInjectHook.java
│   │   │       └── SummeryHook.java     # 注意拼写
│   │   └── resources/
│   │       └── tools.json                # 没人 load
│   └── test/java/com/vanilla/
│       └── AppTest.java                  # 唯一一个测试：assertTrue(true)
└── target/                               # 构建产物
```

---

## 🛣️ 路线图 / 已知问题（一边认账一边排期）

- [x] **API Key 外置**（口头写过）— 等真做了。
- [ ] **API Key 外置**（真做）— `Codey.java` 和 `SpawnSubagentTool.java` 都要改，**两份独立硬编码**。
- [ ] **`SnipMessageCompactor.MAX_MESSAGE_SIZE` 做成不可变** — 当前 `public static int`，随便哪个工具都能改。
- [ ] **`BudgetMessageCompactor` 阈值** — `30_000 / 3` 还是 `30_000`？`200_000 / 10` 还是 `200_000`？二选一。
- [ ] **`HookRegistery` / `SummeryHook` 改名** — 拼写收一收。
- [ ] **`PostToolUse` / `Stop` Hook 第一个订阅者**。
- [ ] **`UserPromptSubmit` Hook** — 文档承诺能改 prompt，实际不能。
- [ ] **`tools.json` 加载** — `src/main/resources/tools.json` 还没人 load。
- [ ] **`TodoWriteTool.getTodos()` 返回不可变视图或防御性拷贝** — 多线程 / 多 Agent 立刻崩。
- [ ] **`ToolManager.subagentToolSpecifications()`** — `tool.name() != "task"` 是引用相等性比较，靠字面量 interning 蒙混过关。
- [ ] **`BashTool` 限制** — 真的 `Stdin`、真的 `PTY`、真的 `kill on timeout` 都还没做。
- [ ] **picocli 接入** — pom 里有依赖，入口仍是手写 `Scanner`。
- [ ] **snprintf, sorry, slf4j** — `ConsoleRenderer` 全用 `System.out.println`；pom 里压根没 slf4j。
- [ ] **单元测试** — 当前只有一个 `assertTrue(true)`。
- [ ] **Hook 测试可重入 stdin** — `SecurityHook.confirmWithUser` 每次重启进程读一次，不适于 CI。
- [ ] **会话存档** — `history` 不落盘。
- [ ] **多模型路由** — 同一个 `BaseURL` 一条路走到底。
- [ ] **Graceful 关闭** — `codey: Agent exits after timeout, is not idempotent` — Ctrl+C 直接撕会话。

---

## 📚 项目文档

设计文档放在 [`docs/`](./docs/) 下：

- [`memory-maintenance-design.md`](./docs/memory-maintenance-design.md) — 记忆维护机制设计：避免 Agent 直接修改记忆文件（对比 Claude Code / Codex）

---

## 🤝 贡献

欢迎提 Issue / PR：

1. Fork & 创建特性分支：`git checkout -b feat/awesome-tool`
2. 保持风格一致：4 空格缩进、Lombok + hutool-json、Java 17 语法。
3. 新增工具时务必在 `ToolManager` 注册，并补充参数校验与错误信息。
4. 新增 Hook 时给出明确 `id()`，避免重复注册。**别再用 `String.contains` 当政策**。
5. 提交前跑：`mvn -q test` ——好，目前只跑 1 个测试。预计用时 ≈ 20ms。

---

## 📄 许可证

内部项目，暂未指定开源许可证。pom 里的 `http://www.example.com` 还在。**别对外发布**，至少在 deny list 和硬编码 key 都解决之前。

---

### 附：和 README 协作写作时的备忘

> 我没夸大。下面每一条都对应你 `git grep` 一遍就能找到的代码。
>
> - API Key：`grep -n sk-cp- src/main/java` 至少两行。
> - `HookRegistery.java`：`ls src/main/java/com/vanilla/hook/`。
> - `MAX_MESSAGE_SIZE`：`grep -n public static int src/main/java/com/vanilla/compact/SnipMessageCompactor.java`。
> - `tool.name() != "task"`：`grep -n "subagentToolSpecifications" src/main/java/com/vanilla/tool/ToolManager.java`。
> - todo 列表共享：`grep -n "private static List" src/main/java/com/vanilla/tool/TodoWriteTool.java`。
> - deny list：`grep -n DENY_LIST src/main/java/com/vanilla/hook/SecurityHook.java`。
>
> 全部原地可验。
