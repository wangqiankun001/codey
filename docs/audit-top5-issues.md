# Codey 审计:Top-5 严重问题

> 范围:`src/main/java/com/vanilla/tool/` 全部 Tool 实现 + 主流程调度。
> 方法:静态阅读 + 关键路径单测验证。
> 输出:按严重度排序的 5 个致命缺陷 + 修复建议。

| # | 模块 | 严重度 | 类别 | 风险概述 |
|---|------|--------|------|---------|
| 1 | `SecurityHook` | 🔴 P0 | 权限/越权 | 缺乏路径白名单与越权防护,任意路径写入 |
| 2 | `EditFileTool` | 🔴 P0 | 资源/正确性 | 多处致命缺陷(详见下文) |
| 3 | `HistoryManager` | 🟠 P1 | 可靠性 | 全内存存储,无持久化,进程崩溃即丢失 |
| 4 | 多处 `catch` | 🟠 P1 | 可观测性 | 异常被静默吞掉,故障排查极难 |
| 5 | `static` 状态 | 🟡 P2 | 可测性/并发 | `static` 字段导致测试污染与并发隐患 |

---

## 问题 #2:`EditFileTool` 致命缺陷(已修复)

`EditFileTool` 在审计中被识别为"高收益低改动"的优先修复目标,核心问题:

### 缺陷清单

| # | 缺陷 | 触发 | 影响 |
|---|------|------|------|
| A | `replace_all=false` 不校验匹配数 | 旧文本出现 0/N(N>1) 次时静默"成功" | 静默 bug,模型/用户不知替换未生效 |
| B | 不拒绝空 `old_string` | 模型生成 `old_string: ""` | `String.replace("","X")` 会把字符串长度膨胀 N 倍 → 文件被破坏 / DoS |
| C | 无字节膨胀护栏 | 大 new_string / 多次 replaceAll | OOM、磁盘爆满 |
| D | 非原子写入 | 进程在写一半崩溃 / 写满磁盘 | 文件被截断成损坏内容 |
| E | 返回信息过简 | 模型只能看到"Successfully" | 模型无法自检(替换了几处?在哪一行?字节变化?) |

### 修复要点

1. **拒绝空 `old_string`**:即使 `replace_all=false`,空 oldString 也会触发 `replace` 的全局插入行为,直接拒绝。
2. **`replace_all=false` 严格校验恰好 1 处**:0 处匹配返回 `"old_string was not found"`;多处匹配返回 `"old_string occurs N times; set replace_all to true..."`,且**不会写文件**。
3. **字节膨胀护栏(双层)**:绝对上限 50MB + 倍率上限 10×(相对原文件大小)。任一命中即拒绝,**保留原文件**。
4. **原子写入**:同目录临时文件 + `FileChannel.force(true)` fsync + `Files.move(..., ATOMIC_MOVE)`。`finally` 块 `Files.deleteIfExists` 清理临时文件,即使后续步骤失败也不污染。
5. **返回 diff 摘要**:`Successfully replaced N occurrence(s) in <path> (first match at line L, byte delta ±D, size A -> B bytes).`
6. **字段别名兼容**:`path`/`file_path`、`old_string`/`oldString`、`new_string`/`newString`、`replace_all`/`replaceAll`,降低模型调用失败概率。

### 测试覆盖

`src/test/java/com/vanilla/tool/EditFileToolTest.java` 新增 18 个用例覆盖全部修复点:

- 正常单次/多处替换、空 oldString 拒绝、空 newString 合法、0 匹配拒绝、歧义拒绝
- `replace_all=true` 多处替换、camelCase 字段、`file_path` 别名
- 倍率上限分支 + 绝对上限分支(后者通过测试钩子 `overrideMaxResultBytes` 覆写阈值,避免堆上构造 50MB+ 字符串)
- 原子写入不遗留临时文件(成功 + 失败两条路径)
- UTF-8 字节边界、文件不存在、返回行号、字节 delta
- 非法 JSON、缺路径、路径遍历冒烟

### 验证

```
mvn test -Dtest=EditFileToolTest   →  Tests run: 18, Failures: 0, Errors: 0
mvn test                          →  Tests run: 19, Failures: 0, Errors: 0
```

### 实施日期

2025(本次代码审阅与修复周期)。

---

## 后续建议(本轮未实施)

- **#1 `SecurityHook`**:在 hook 层加路径白名单(`allowedRoots`) + 符号链接解析后二次校验。
- **#3 `HistoryManager`**:改用磁盘追加写(WAL 风格),崩溃后可重放。
- **#4 异常吞掉**:全局替换 `catch (Exception e) {}` 为结构化日志 + 重抛或降级。
- **#5 `static` 状态**:把可变状态收敛到 `ToolContext` 对象,按会话/进程隔离。

优先级建议:**#1 > #4 > #3 > #5**。