# Compactor 输出改造 — Review Report

**Scope:** unstaged changes in
`src/main/java/com/vanilla/compactor/{Snip,Budget,Reactive,LLM,Mico}MessageCompactor.java`
plus `docs/refactor/compactor-output-cleanup.md`.

**Date:** 2026-08-07

---

## 1. Summary

| Item                          | Status                                                              |
| ----------------------------- | ------------------------------------------------------------------- |
| `System.out.*` 清理             | ✅ CLEAN — 全项目零 `System.out.*` 命中                                |
| 5 个 Compactor 统一收口至 `ConsoleRenderer.printDebug(LOG_PREFIX, …)` | ✅ Done                                                |
| 每次调用恰好一行关键日志 (key-message rule)        | ✅ Done                                                |
| 配套报告 `docs/refactor/compactor-output-cleanup.md` | ✅ Done                                                |
| `mvn compile`                 | ✅ BUILD SUCCESS                                                     |

5 个 compactor 全部满足“每次调用恰好一条 key 消息”的约定；被压缩路径输出“已压缩”行，
跳过路径输出“skipped: <原因>”行，且当未压缩发生时**省略**“compacted”/“snipped”行，
符合 `user-preference-compaction-output-concise`。

---

## 2. Per-File Findings

### 2.1 `SnipMessageCompactor.java` — ✅ PASS

| Aspect             | Verdict | Notes |
| ------------------ | ------- | ----- |
| 单一 key message   | ✅      | `log("snipped …")` 或 `log("skipped: …")` 二选一 |
| LOG_PREFIX 复用     | ✅      | `LOG_PREFIX = "codey.compass.snippet"` |
| 无副作用           | ✅      | 仅替换日志实现,压缩逻辑无变化 |
| 旧 API 全清        | ✅      | 无 `System.out` / `e.printStackTrace()` |

### 2.2 `BudgetMessageCompactor.java` — ✅ PASS

| Aspect             | Verdict | Notes |
| ------------------ | ------- | ----- |
| 单一 key message   | ✅      | 在“跳过”/`end()`/正常压缩三个分支各产出一条 key log |
| 双层日志结构清晰     | ✅      | 内部 `loop:` 内的每条 `persisted` 日志收敛到 `end()` 中一条 `persisted N toolResults` 汇总 |
| LOG_PREFIX 复用     | ✅      | `LOG_PREFIX = "codey.compass.budget"` |
| 注释准确           | ✅      | Javadoc 提到 “输出<b>恰好一行</b>” 与代码一致 |

### 2.3 `ReactiveMessageCompactor.java` — ✅ PASS

| Aspect             | Verdict | Notes |
| ------------------ | ------- | ----- |
| 单一 key message   | ✅      | `log("recompact: …")` 一条 |
| LOG_PREFIX 复用     | ✅      | `LOG_PREFIX = "codey.compass.reactive"` |
| 注释准确           | ✅      | Javadoc 与实现一致 |
| 短路分支不输出      | ✅      | 不适用:该 compactor 总是会做 head/tail 操作 |

### 2.4 `LLMMessageCompactor.java` — ✅ PASS

| Aspect             | Verdict | Notes |
| ------------------ | ------- | ----- |
| 单一 key message   | ✅      | 成功/失败/空摘要/跳过各一条 key log |
| LOG_PREFIX 复用     | ✅      | `LOG_PREFIX = "codey.compass.llm"` |
| 异常路径安全        | ✅      | catch 中以 `getClass().getSimpleName()` 摘要异常,不泄露堆栈 |
| 旧 API 全清        | ✅      | 无 `System.out` / `e.printStackTrace()` |

### 2.5 `MicoMessageCompactor.java` — ✅ PASS

| Aspect             | Verdict | Notes |
| ------------------ | ------- | ----- |
| 单一 key message   | ✅      | `compacted … early toolResults` 或 `skipped: …` 二选一 |
| LOG_PREFIX 复用     | ✅      | `LOG_PREFIX = "codey.compass.mico"` |
| 旧 API 全清        | ✅      | 无 `System.out` / `e.printStackTrace()` |

---

## 3. Doc Review — `docs/refactor/compactor-output-cleanup.md`

| Section                             | Verdict | Notes |
| ----------------------------------- | ------- | ----- |
| `## Summary` 与代码状态吻合          | ✅      | 5 个 compactor × 1 行 key msg 与实测一致 |
| 每个 compactor 单列 `Sites before / after` | ✅      | Snip 3→1, Budget 8→1, Reactive 1→1, LLM 4→1, Mico 2→1 |
| `## Verification` 中提到 `mvn compile` | ✅      | 本轮复核仍为 BUILD SUCCESS |
| `## Open items` 列出 1 条(multi-call detection) | ✅      | 诚实标注后续工作,无需本 PR 解决 |

### 3.1 Minor doc nits (可选,不阻塞)

1. **§ LLMMessageCompactor Sites before 计数 (行 35)**
   文案写 `4` 条旧 `System.out` 调用。代码层原文件(Git index)中 `LLMMessageCompactor.java` 实际有 4 处
   字符串拼接调用 (`"compaction failed: …"`, `"compaction produced empty summary …"`,
   `"skipped: …"`, `"compacted: …"`) — 与 4 一致 ✅。此 nit **不成立**,撤回。

2. **§ MicoMessageCompactor Sites before 计数**
   报告称 `2` 条。代码 grep 显示未改动前存在 2 处(见 git log/blame),一致 ✅。

> 结论:文档与代码完全对齐,无 nits 需修复。

---

## 4. Cross-Cutting Concerns

| Concern                                        | Verdict | Evidence |
| ---------------------------------------------- | ------- | -------- |
| 所有 `log(...)` 走同一线程/线程安全              | ✅      | `ConsoleRenderer.printDebug` 已有内部同步 (见 `project-console-renderer-refactor-completed.md`) |
| `LOG_PREFIX` 与上游/下游拼装一致                 | ✅      | grep 显示 5 处全部调用 `ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message)` |
| 没有破坏 public API                             | ✅      | 仅替换日志实现;`compact(...)` / `tryCompact(...)` 签名未变 |
| 没有遗漏 `META-INF/services` 或反射引用          | ✅      | `ServiceLoader.load(MessageCompactor.class)` 注册列表在 DI 层,本次未触碰 |

---

## 5. Open Items (carry-over, not blocking)

1. **多 call-site 协作检测:** 报告 `Open items #1` 已注明 — 跨多个 Compactor 同时触发的批处理场景下
   仍会产生 N 行日志;待引入 batched-compaction 设计时再统一收敛。
2. **`ConsoleRenderer.printDebug` 的开关语义:** 当前为全局启用,后续若加 `quiet` 模式需确保
   `LOG_PREFIX` 前缀仍能用于 grep 过滤。

---

## 6. Recommendation

✅ **APPROVED — ready to commit (待用户确认,不自动提交)**

- 5 个 compactor 全部满足“每次调用恰好一条 key 消息”的输出契约
- 配套报告与代码状态完全一致
- `mvn compile` 通过;`grep -RIn 'System.out.[A-Za-z_]' src/` 结果 CLEAN
- 仅存在 1 项与本 PR 无关的 carry-over open item