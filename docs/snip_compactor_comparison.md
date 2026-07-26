# SnipMessageCompactor 与 code.py snip_compact 对照报告

> 对比对象：
> - Python（Anthropic 标准）: `code.py` 中的 `snip_compact`（L1 中段裁剪）
> - Java（OpenAI 标准 - langchain4j）: `src/main/java/com/vanilla/compact/SnipMessageCompactor.java` 中的 `snipCompact`
>
> 工程现状：`Codey.java` 使用 `OpenAiChatModel`，工具结果是独立的 `ToolExecutionResultMessage`（`role=tool`），而不是 Anthropic 风格下嵌在 `user` 消息里的 `tool_result` 块。

---

## 1. 代码骨架

### 1.1 Python（Anthropic 标准）

```python
def _message_has_tool_use(msg):
    if msg.get("role") != "assistant": return False
    content = msg.get("content")
    if not isinstance(content, list): return False
    return any(_block_type(block) == "tool_use" for block in content)

def _is_tool_result_message(msg):
    if msg.get("role") != "user": return False
    content = msg.get("content")
    if not isinstance(content, list): return False
    return any(isinstance(block, dict) and block.get("type") == "tool_result"
               for block in content)

def snip_compact(messages, max_messages=50):
    if len(messages) <= max_messages: return messages
    keep_head, keep_tail = 3, max_messages - 3
    head_end, tail_start = keep_head, len(messages) - keep_tail

    # 头侧保护：最后一条 head 是 assistant.tool_use，向后跳过紧随的 tool_result 对
    if head_end > 0 and _message_has_tool_use(messages[head_end - 1]):
        while head_end < len(messages) and _is_tool_result_message(messages[head_end]):
            head_end += 1

    # 尾侧保护：tail_start 自身是 tool_result 且前一条是 assistant.tool_use，回退 1
    if (tail_start > 0 and tail_start < len(messages)
            and _is_tool_result_message(messages[tail_start])
            and _message_has_tool_use(messages[tail_start - 1])):
        tail_start -= 1

    if head_end >= tail_start:
        return messages
    snipped = tail_start - head_end
    return messages[:head_end] + [{"role": "user", "content": f"[snipped {snipped} messages]"}] + messages[tail_start:]
```

### 1.2 Java（OpenAI 标准 - langchain4j）

```java
public static List<ChatMessage> snipCompact(List<ChatMessage> history) {
    int size = history.size();
    if (size < MAX_MESSAGE_SIZE) return history;
    int keepTail = MAX_MESSAGE_SIZE - KEEP_HEAD_SIZE;   // = 48
    int startIdx = KEEP_HEAD_SIZE;                     // = 2
    int endIdx = size - keepTail;

    // 尾侧：向后回退连续的 TOOL_EXECUTION_RESULT
    int toolPullback = 0;
    while (endIdx > startIdx && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(endIdx).type())) {
        endIdx--;
        toolPullback++;
    }

    int snippedCount = endIdx - startIdx;
    if (snippedCount <= 0) return history;

    List<ChatMessage> newHistory = new ArrayList<>();
    newHistory.addAll(history.subList(0, startIdx));
    newHistory.add(UserMessage.from(String.format("[snipped %d messages]", snippedCount)));
    newHistory.addAll(history.subList(endIdx, size));
    history.clear();
    history.addAll(newHistory);
    return history;
}
```

---

## 2. 已对齐的部分（语义相同）

| 维度 | 行为 |
|---|---|
| 中段裁剪后注入 `[snipped N messages]` 标记 | ✅ 两者都做 |
| 标记消息的角色 | ✅ 都用 `UserMessage` / `role=user` |
| 占位文案 | ✅ `[snipped {n} messages]` 完全一致 |
| 裁切数 ≤ 0 时原样返回 | ✅ 两者都有早退 |
| "前 KEEP_HEAD 条 + 后 keepTail 条" 保留策略 | ✅ |

---

## 3. 差异 / Bug 清单

### 🔴 差异 1：头侧 tool 配对保护完全缺失（对 OpenAI 是破坏性的）

**Python 行为**：当最后一条 head 是 `assistant.tool_use` 时，会向后跳过紧随的工具结果，避免孤立 tool_use。

**Java 行为**：`startIdx` 是硬切，**没有任何向后跳过 `TOOL_EXECUTION_RESULT` 的逻辑**。

**后果**：切点若恰好落在 `AiMessage(tool_calls=…)` 之后，对应的 `ToolExecutionResultMessage` 会被裁掉，而 `AiMessage.toolExecutionRequests()` 仍保留在 `subList(0, startIdx)` 中。OpenAI Chat Completions 严格要求每个 `tool_call_id` 都对应一个 `role=tool` 的响应，否则返回：

```
400 invalid_request_error
messages with role 'tool' must be a response to a preceeding message with tool_calls
```

整轮 agent 会直接崩。

### 🟠 差异 2：KEEP_HEAD 不一致

| | Python | Java |
|---|---|---|
| `keep_head` / `KEEP_HEAD_SIZE` | **3** | **2** |
| `keep_tail` | 47 | 48 |

裁切边界错位 1 个消息，与差异 1 叠加更容易切到孤立的 `AiMessage(tool_calls)` 之后。

### 🟠 差异 3：尾侧保护的粒度与判定条件不同

**Python**：
- 只回退 **1 条**。
- 前置条件：`messages[tail_start]` 是 `tool_result` 且 `messages[tail_start-1]` 是 `assistant.tool_use`（必须是配对的孤儿 tool_result 才回退）。

**Java**：
- 不校验前一条，只要结尾是 `TOOL_EXECUTION_RESULT` 就**连回退 N 条**。
- 没有上限保护，也没有"上一条是不是对应 assistant"的合法性校验。

**风险**：若历史末尾混入了孤立的 `TOOL_EXECUTION_RESULT`（例如 reactive compact 后遗留），Java 会无脑吞掉更多上下文；Python 因为只回退 1 条、且校验前一条助手消息，更稳健。

### 🟡 差异 4：触发条件语义略不同

| Python | Java |
|---|---|
| `if len(messages) <= max_messages: return` | `if (size < MAX_MESSAGE_SIZE) ... return` |

当 `size == 50` 时：Python 早退；Java 进入 `snippedCount = 0` 分支，靠 `snippedCount <= 0` 兜底。

**最终行为一致**，但读起来易困惑。

### 🟡 差异 5：占位消息处理风格

- Python：`{"role": "user", "content": "..."}`（字符串 content）
- Java：`UserMessage.from("[snipped N messages]")`

在 OpenAI 下都允许，只是风格差异，不影响功能。

---

## 4. 对 OpenAI 标准的影响总结

| 风险点 | 等级 | 触发条件 |
|---|---|---|
| 头侧裁切切断 tool_use ↔ tool_result 配对 → API 400 | 🔴 高 | KEEP_HEAD 恰好切在 `AiMessage(tool_calls)` 之后 |
| 尾侧无校验地连续回退孤立 tool_result | 🟠 中 | 历史末尾已经存在孤立 tool_result |
| 触发条件语义不一致 | 🟡 低 | 已被兜底分支覆盖，行为等价 |
| 占位消息风格 | 🟢 无影响 | 仅风格差异 |

---

## 5. 修复建议（最小改动版）

把 Python 的头侧保护对位补齐到 Java，并把 `KEEP_HEAD_SIZE` 与 Python 对齐：

```java
public static List<ChatMessage> snipCompact(List<ChatMessage> history) {
    final int size = history.size();
    if (size < MAX_MESSAGE_SIZE) return history;

    int startIdx = KEEP_HEAD_SIZE;                       // 建议改回 3
    int endIdx   = size - (MAX_MESSAGE_SIZE - KEEP_HEAD_SIZE);

    // ── 头侧保护：startIdx 切在 AiMessage(tool_calls) 之后，把 startIdx 推到所有
    //    紧随的 TOOL_EXECUTION_RESULT 之后，保证 tool_use ↔ tool_result 配对不被打断
    int headIdx = startIdx;
    if (headIdx > 0 && ChatMessageType.AI.equals(history.get(headIdx - 1).type())) {
        AiMessage prev = (AiMessage) history.get(headIdx - 1);
        if (prev.hasToolExecutionRequests()) {
            while (headIdx < size
                    && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(headIdx).type())) {
                headIdx++;
            }
        }
    }

    // ── 尾侧保护：endIdx 落在 TOOL_EXECUTION_RESULT 上，连续向前回退整段 tool_result
    int tailIdx = endIdx;
    while (tailIdx > headIdx
            && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(tailIdx).type())) {
        tailIdx--;
    }

    int snippedCount = tailIdx - headIdx;
    if (snippedCount <= 0) return history;

    List<ChatMessage> compact = new ArrayList<>(history.subList(0, headIdx));
    compact.add(UserMessage.from(String.format("[snipped %d messages]", snippedCount)));
    compact.addAll(history.subList(tailIdx, size));

    history.clear();
    history.addAll(compact);
    return history;
}
```

要点：
1. 用 `AiMessage.hasToolExecutionRequests()` 替代 Python 的 `_message_has_tool_use`，逻辑等价但适配 langchain4j 类型。
2. `KEEP_HEAD_SIZE` 从 2 改回 3，与 Python 一致；写死常量无副作用。
3. 尾侧保留现有的"连续回退"策略，但加 `tailIdx > headIdx` 上限避免越界。

---

## 6. 结论

`SnipMessageCompactor.snipCompact` **整体算法和 Python 版一致**，但**头侧缺少 `assistant.tool_use → tool_result` 配对保护**，叠加 `KEEP_HEAD_SIZE=2`（Python 是 3），在 OpenAI 标准下会把配对切断导致 API 报错。

修复建议：补齐头侧向后跳过 `TOOL_EXECUTION_RESULT` 的逻辑，并把 `KEEP_HEAD_SIZE` 改回 3，保持与 `code.py` 一致。
