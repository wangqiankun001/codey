# 项目压缩器介绍

`src/main/java/com/vanilla/compactor/` 下共 5 个压缩器实现，按触发条件与是否调用 LLM 分两类。

## 一句话总览

> 5 个互补的压缩器：**基于字符/条数阈值**（Budget、Snip、Mico —— 纯本地 O(n)、零 LLM 调用、快）+ **基于 LLM 摘要**（LLM、Reactive —— 调大模型生成语义摘要、慢但语义无损）。

---

## 逐个简介

### 1. `BudgetMessageCompactor.toolResultBudget` — 单条/累计超限自动落盘

- **触发条件**：单条 `TOOL_EXECUTION_RESULT` 文本 > 30 000 字符，**或** 整段历史中所有 tool_result 累计 > 200 000 字符
- **策略**：定位末尾连续的 `TOOL_EXECUTION_RESULT` 段 → 按"长度从大到小"逐个落盘到 `.codey/task_outputs/tool-results/{toolUseId}.txt`，正文替换为

  ```xml
  <persisted-output toolUseId="{id}" file="{path}" previewSize="{n}">{前 2 000 字符预览}</persisted-output>
  ```

  落盘的条数按累计字符数扣回到阈值以下才停。
- **是否落盘**：✅ `.codey/task_outputs/tool-results/`
- **日志前缀**：`[compactor]`

### 2. `LLMMessageCompactor.llmCompact` — 累计超限走 LLM 摘要

- **触发条件**：所有消息纯文本累计长度 > 50 000 字符
- **策略**：
  1. 先把整段历史以 JSONL 落盘到 `.codey/transcript/{timestamp}.jsonl`（事后审计用）
  2. 拼 `system = "You are a conversation compactor."` + `user = COMPACT_INSTRUCTION + JSON` 调大模型
  3. 取回摘要后，清空 `history`、**保留所有 `SystemMessage`**、把摘要作为新的 `UserMessage("[Compressed summary]\n" + summary)` 注入头部
  4. LLM 调用失败或返回空摘要时，**保留原 history 不动**，仅留下 transcript 供排查
- **是否落盘**：✅ `.codey/transcript/`
- **日志前缀**：`[llm]`
- **实现亮点**：用 `COMPACT_INSTRUCTION + "\n" + conversation` 字符串拼接（**不**走 `String.format`），避免 JSON 中的 `%` 触发 `UnknownFormatConversionException`；`textLength` 用 `hasSingleText()` + `contents()` 聚合，多模态下也不会抛 `IllegalStateException`。

### 3. `ReactiveMessageCompactor.reactiveCompact` — 保留末尾工具结果链，对前面调 LLM

- **触发条件**：无显式阈值，由调用方（如流式收尾时）手动触发
- **策略**：
  1. 先同样落盘 transcript（复用 `LLMMessageCompactor` 的 `.codey/transcript/`）
  2. 从历史末尾**向前**找到第一个**非** `TOOL_EXECUTION_RESULT` 的下标 `i`
  3. `tail = history[i, size)`（保留末尾的工具调用结果链不被摘要破坏）
  4. `head = history[0, i)` → 递归调 `LLMMessageCompactor.llmCompact` 生成摘要
  5. 把 `compactedHead + tail` 拼回 `history`
- **是否落盘**：✅ 透传 transcript
- **日志前缀**：`[reactive]`
- ⚠️ **已知 bug**：当 `history` 全为 `TOOL_EXECUTION_RESULT` 时，`for (i = history.size() - 1;; i--)` 会一直减到 `-1` 然后 `history.get(-1)` 抛 `IndexOutOfBoundsException`。

### 4. `SnipMessageCompactor.snipCompact` — 超条数硬切，保留头尾

- **触发条件**：`history.size() >= 60`（`MAX_MESSAGE_SIZE = 60`）
- **策略**：保留 `head = history[0, 2)` + `tail = history[62-60, size)`（即 `KEEP_HEAD_SIZE=2`、`KEEP_TAIL_SIZE=58`），中间整段替换为一条 `UserMessage("[snipped N messages]")`。cut 点 `endIdx` 若正好落在 `TOOL_EXECUTION_RESULT` 上会**向前回退**，保证不会把 `assistant(tool_call) ↔ tool_result` 对拆开。
- **是否落盘**：❌ 纯 in-memory 操作
- **日志前缀**：`[snip]`

### 5. `MicoMessageCompactor.micoCompact` — 早期长 tool_result 抹平

- **触发条件**：历史中累计 `TOOL_EXECUTION_RESULT` ≥ 5 条（`KEEP_RECENT = 5`）
- **策略**：扫描所有 `TOOL_EXECUTION_RESULT` 的下标 → 取**更早的** `total - 5` 条作为候选 → 仅对文本长度 > 120 字节（`TOOL_USE_SIZE_THRESHOLD`）的条目，用 `toBuilder().contents(TextContent.from("[Earlier tool result compacted. Re-run if needed.]"))` 原位替换。短结果保持原样，不做"过度压缩"。
- **是否落盘**：❌ 纯 in-memory 操作
- **日志前缀**：`[mico]`

---

## 设计共性

| 维度 | 约定 |
|---|---|
| **日志输出** | 所有压缩器用 `[<name>]` 前缀（`[compactor]` / `[llm]` / `[reactive]` / `[snip]` / `[mico]`），且都 `System.out.flush()`，交互式终端实时可见 |
| **落盘序列化** | `Budget` / `LLM` / `Reactive` 统一走 `ChatMessageJsonConvertor.INSTANCE::convert`，避免 `ToolExecutionResultMessage.text()` 在多模态下抛 `IllegalStateException` |
| **原地修改** | `llmCompact` / `snipCompact` / `micoCompact` / `reactiveCompact` 都**就地修改传入的 `history` 列表并返回同一引用**，便于链式调用 |
| **SystemMessage 守护** | `LLMMessageCompactor.llmCompact` 严格保留所有 `SystemMessage`，压缩不会破坏系统提示 |
| **失败兜底** | LLM 调用失败 / 摘要为空时均不修改 `history`，保留原上下文（仅 LLM 类压缩器有此特性） |
| **零 LLM 路径** | `Snip` 与 `Mico` 都是 O(n) 同步、零 IO、零大模型开销，适合每轮低成本触发 |

---

## 已知风险（与 `severe-problems-report.md` 一致）

1. **`ReactiveMessageCompactor.reactiveCompact`** — 无限下标循环 bug。当 `history` 全部为 `TOOL_EXECUTION_RESULT` 时，循环到 `i = -1` 仍不终止，`history.get(-1)` 抛 `IndexOutOfBoundsException`。
   - **建议修复**：把 `for (i = history.size() - 1;; i--)` 改为 `for (i = history.size() - 1; i >= 0; i--)`，并在循环外加 fallback（`i < 0` 时把整段历史作为 tail，让 `head` 为空）。
2. **`BudgetMessageCompactor`** — TOCTOU 竞态。`Files.exists(path)` 与随后 `Files.writeString(path, ...)` 之间存在时间窗口，并发触发时可能两个线程同时落盘同一 `toolUseId`，后写覆盖前写。
   - **建议修复**：去掉 `Files.exists` 检查，直接 `Files.writeString`；或在写入前用 `Files.createFile` 原子创建，捕获 `FileAlreadyExistsException` 后改名为 `.{id}.{ts}.txt`。

---

需要我接着修这两个 bug 吗？或者继续推进 `severe-problems-report.md` 里 Top5 剩余的 #3（`SecurityHook` 路径前缀绕过）、#4（`MemoryManager.consolidateMemories` 先删后写）、#5（即上面的 `ReactiveMessageCompactor`）？