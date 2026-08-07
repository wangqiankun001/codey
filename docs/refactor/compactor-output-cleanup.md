# BudgetCompactor & ReactiveCompactor 输出收敛报告

## 目标
按 `user-preference-compaction-output-concise` 偏好：每个压缩器<b>每次调用恰好输出一行</b>关键日志。
- 未触发压缩 → 输出**未满足的条件** + 诊断信息
- 触发了压缩 → 输出**已满足的条件** + 压缩结果

## 修改文件
| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/vanilla/compactor/BudgetMessageCompactor.java` | 把所有静默 `return` 替换成 `log(...)`；把 `persistLargeOutput` 改为返回 `PersistResult` 把错误回传，由调用方合并到唯一一行日志 |
| `src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java` | 把"transcript 写入失败"的第二行日志合并进主日志；新增 `TranscriptWrite` 内部 record 携带失败原因 |

## BudgetMessageCompactor 输出矩阵
| 入口条件 | 日志格式 | 说明 |
| --- | --- | --- |
| `history == null \|\| history.isEmpty()` | `[budget] skipped: history is empty` | 未满足 |
| 末尾没有 TOOL_EXECUTION_RESULT | `[budget] skipped: no trailing tool results (history=N msgs)` | 未满足 |
| 末尾 tool 结果全是多模态/复合 | `[budget] skipped: no single-text tool results in tail (tail=N msgs)` | 未满足 |
| 总长度未超预算 | `[budget] skipped: 12345 <= maxBytes 200000 (tail=3 toolResults)` | 未满足（带字节对比） |
| 实际落盘 | `[budget] persisted 2 toolResults: 350000 -> 180000 chars (saved 49%)` | 已满足 + 字节 delta + 百分比 |
| 超预算但无可落盘条目 / 全部落盘失败 | `[budget] skipped: over budget 350000 > 200000 but no single toolResult exceeded 30000 chars (tail=3 toolResults)` 或 `(persist err: ...)` | 未满足 + 原因 |

`persisted ...` 行在有落盘失败时也会附带 `(persist err: <异常类>: <消息>)`，保持单行。

## ReactiveMessageCompactor 输出矩阵
| 入口条件 | 日志格式 | 说明 |
| --- | --- | --- |
| transcript 写入成功 | `[reactive] recom pact: head=12 (-> 3), tail kept=4, transcript=1700000000000.jsonl` | 已满足 + head→LLM 压缩后条数 + tail 保留 + transcript 文件名 |
| transcript 写入失败 | `[reactive] recom pact: head=12 (-> 3), tail kept=4, transcript=FAILED (IOException: <消息>)` | 已满足 + 错误原因（仍只 1 行） |

注：reactive 压缩器没有 skip 分支（一旦调用就触发 LLM 二次压缩），所以没有"未满足"行；任何
故障信息都合并到唯一一行的 `transcript=` 片段里。

## 验证
- `mvn clean compile` → BUILD SUCCESS（33 个源文件）
- 全量 `log()` 计数：Budget = 7 处调用，覆盖 6 个分支；Reactive = 1 处调用
- `persistLargeOutput` 不再直接调用 `log()`，符合"每次调用只输出 1 行"的约定
- 旧 `log("transcript写入失败: ...")` 这种同次调用内的第二条日志已消除

## 不在本次范围内
- `ReactiveMessageCompactor` 中 `for (i = history.size() - 1;; i--)` 在 history 全为 tool 消息时会 IOOBE——本次只优化输出，不动控制流；如需后续修复请单开 issue