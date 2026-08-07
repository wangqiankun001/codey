# `System.out.*` 重构报告

## 目标
将 `com.vanilla` 主代码与测试代码内所有直接调用 `System.out.*` 的位置，改为通过 `ConsoleRenderer` 统一输出，避免业务逻辑绕过既有的渲染层。

## 检索结果（重构前）
最终扫描 `src/main/java` 与 `src/test/java`，得到 **19 个** 实际写出的调用点（不含 `new ConsoleRenderer(System.out)` 这种把 `System.out` 当作 `PrintStream` 依赖注入的合法用法）。

| 类别 | 含义 | 数量 |
| --- | --- | --- |
| A. 错误提示 | 业务异常时输出给用户看的错误信息 | 8 |
| B. 调试日志 | 压缩器在压缩过程中追踪内部状态的输出 | 10 |
| C. 调试遗留 | 工具类里写完没删的 `main()`，只在开发期手动跑过 | 1 |
| **合计** | | **19** |

### A. 错误提示（8 处）
全部出现在 `src/main/java/com/vanilla/memory/MemoryManager.java`：

| 行号 | 原文 | 替换为 |
| --- | --- | --- |
| 94 | `System.out.println("memory load failed.");` | `ConsoleRenderer.getShared().printError("memory load failed.");` |
| 125 | `System.out.println("读取记忆文件作为字符串失败" + fileName + ".md");` | `ConsoleRenderer.getShared().printError("读取记忆文件作为字符串失败" + fileName + ".md");` |
| 152 | `System.out.println("[extractMemory] ai提取记忆内容格式不符合格式: " + aiMessage.text());` | `ConsoleRenderer.getShared().printError("[extractMemory] ai提取记忆内容格式不符合格式: " + aiMessage.text());` |
| 170 | `System.out.println("记忆文件创建失败: " + memory.name());` | `ConsoleRenderer.getShared().printError("记忆文件创建失败: " + memory.name());` |
| 186 | `System.out.println("文件索引创建失败");` | `ConsoleRenderer.getShared().printError("文件索引创建失败");` |
| 192 | `System.out.println("文件索引内容写入失败");` | `ConsoleRenderer.getShared().printError("文件索引内容写入失败");` |
| 248 | `System.out.println("[consolidateMemories] ai提取记忆内容格式不符合格式: " + aiMessage.text());` | `ConsoleRenderer.getShared().printError("[consolidateMemories] ai提取记忆内容格式不符合格式: " + aiMessage.text());` |
| 258 | `System.out.println("过期记忆删除失败");` | `ConsoleRenderer.getShared().printError("过期记忆删除失败");` |

这一类直接复用了 `ConsoleRenderer` 已有的 `printError(String)`，不需要新增 API，只在 MemoryManager 顶部加一行 `import com.vanilla.util.ConsoleRenderer;`。

### B. 调试日志（10 处）
5 个压缩器各占 2 处（一句 `println` + 一句 `flush`），共 10 个调用点：

| 文件 | 原 `log()` 实现 |
| --- | --- |
| `compactor/ReactiveMessageCompactor.java` | `System.out.println(LOG_PREFIX + message); System.out.flush();` |
| `compactor/BudgetMessageCompactor.java` | 同上 |
| `compactor/SnipMessageCompactor.java` | 同上 |
| `compactor/LLMMessageCompactor.java` | 同上 |
| `compactor/MicoMessageCompactor.java` | 同上 |

为了既保留「来源前缀 + 自动 flush」两个细节，又能让日志走渲染层，新增两个重载：
- `ConsoleRenderer.printDebug(String prefix, String message)`：带前缀，使用 DIM 颜色，自动 flush。
- `ConsoleRenderer.printDebug(String message)`：无前缀，委托给上面那个。

替换后每个压缩器的 `log()` 简化为单行：
```java
ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message);
```

### C. 调试遗留（1 处）
`src/main/java/com/vanilla/tool/LoadSkillTool.java` 文件底部有一段 `public static void main(String[] args)`，正文只有一个 `System.out.println(...)`，是开发期手动跑过的测试入口。直接删除整个 `main()` 方法。

## 新增 / 修改 API
仅 `ConsoleRenderer` 一处新增（其余都复用现有方法）：

```java
public void printDebug(String prefix, String message) {
    String header = (prefix == null || prefix.isEmpty()) ? "" : prefix + " ";
    out.println(style(DIM, "  " + header + (message == null ? "" : message)));
    out.flush();
}

public void printDebug(String message) {
    printDebug("", message);
}
```

颜色使用 DIM：和普通提示可区分，但不会喧宾夺主。

## 改动文件清单
| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/vanilla/util/ConsoleRenderer.java` | 新增 `printDebug` 两个重载 |
| `src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java` | 加 import，`log()` 改为 `printDebug` |
| `src/main/java/com/vanilla/compactor/BudgetMessageCompactor.java` | 同上 |
| `src/main/java/com/vanilla/compactor/SnipMessageCompactor.java` | 同上 |
| `src/main/java/com/vanilla/compactor/LLMMessageCompactor.java` | 同上 |
| `src/main/java/com/vanilla/compactor/MicoMessageCompactor.java` | 同上 |
| `src/main/java/com/vanilla/memory/MemoryManager.java` | 加 import，8 处 `printError` 替换 |
| `src/main/java/com/vanilla/tool/LoadSkillTool.java` | 删除 `main()` 方法 |

合计 8 个文件，新增 +0 / 修改 -8 / 删除 -1。

## 验证
### 静态扫描
```
grep -rn "System\.out" src/main/java src/test/java
```
只剩 3 处，全部是 `new ConsoleRenderer(System.out)`——把 `System.out` 作为 `PrintStream` 注入，不是输出调用，**符合预期**。

### `System.err` / `printf` / `format`
```
grep -rn "System\.err"       → 0
grep -rn "System\.out\.printf" → 0
grep -rn "System\.out\.format" → 0
```

### 编译
```
mvn clean compile
→ BUILD SUCCESS（33 个源文件）
```

## 复审复查（"再检查一下"）
按用户要求重新扫描，发现一处与本次重构同类的「绕过 ConsoleRenderer」味道，但**严格来说不属于 `System.out.*` 范畴**，已单独列出：

⚠️ **`src/main/java/com/vanilla/skill/SkillManager.java:35`**
```java
} catch (IOException e) {
    e.printStackTrace();
}
```
- 同样绕过了 `ConsoleRenderer`，直接写到 stderr。
- 出现位置：静态初始化块扫描 `.codey/skills` 目录时，外层目录列表失败。
- 当前未处理。三种可选方案：
  1. 替换为 `ConsoleRenderer.getShared().printError("扫描 skills 目录失败: " + e.getMessage());`（最简，丢失栈）
  2. 在 `ConsoleRenderer` 新增 `printException(String context, Throwable t)`，保留完整栈
  3. 保持原样，与本任务分工不变

等待用户拍板。

## 结论
- 19 个 `System.out.*` 实际输出点全部清除。
- 14 处替换统一走 `ConsoleRenderer`（8 `printError` + 5 `printDebug`，加上 MicoMessageCompactor 中本身已用 `printError`，实际新增 13 个调用点 + 1 个 `main()` 删除）。
- 编译通过，行为兼容（`printDebug` 仍自动 flush，输出流仍是 `System.out`，前缀与原版一致）。
- 复审发现一处 `printStackTrace` 绕过渲染层，已列入待办，等用户决定后再处理。
