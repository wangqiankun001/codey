# 项目中超过 JDK 8 的语法/特性清单

## 项目编译配置

`pom.xml` 中:

```xml
<maven.compiler.source>17</maven.compiler.source>
<maven.compiler.target>17</maven.compiler.target>
```

所以项目实际目标是 **JDK 17**,下列高版本特性均为有意使用。

---

## JDK 11 特性

### `Files.readString` / `Files.writeString`(JDK 11+)

```java
String data = Files.readString(path, StandardCharsets.UTF_8);
Files.writeString(path, content, StandardCharsets.UTF_8);
```

出现位置:
- `src/main/java/com/vanilla/memory/MemoryManager.java`(多处)
- `src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java`
- `src/main/java/com/vanilla/compactor/BudgetMessageCompactor.java`
- `src/main/java/com/vanilla/compactor/LLMMessageCompactor.java`
- `src/main/java/com/vanilla/tool/EditFileTool.java`
- `src/main/java/com/vanilla/tool/WriteFileTool.java`
- `src/main/java/com/vanilla/tool/ReadFileTool.java`
- `src/main/java/com/vanilla/skill/SkillManager.java`

### `Path.of(...)`(JDK 11+)

```java
Path dir = Path.of(System.getProperty("user.dir"), ".codey", "memories");
```

出现位置:
- `src/main/java/com/vanilla/memory/MemoryManager.java`
- `src/main/java/com/vanilla/hook/SecurityHook.java`
- `src/main/java/com/vanilla/tool/EditFileTool.java`
- `src/main/java/com/vanilla/tool/WriteFileTool.java`
- `src/main/java/com/vanilla/tool/ReadFileTool.java`

### `String.isBlank()`(JDK 11+)

```java
if (str.isBlank()) { ... }
```

出现位置:`MemoryManager.java`、`ReactiveMessageCompactor.java`、`LLMMessageCompactor.java`、`SkillManager.java`、`WriteFileTool.java`、`ReadFileTool.java`、`EditFileTool.java`、`HookManager.java`、`HookEngine.java` 等。

### `String.strip()` / `stripLeading()` / `stripTrailing()`(JDK 11+)

```java
Path target = Path.of(pathArgument.strip());
```

出现位置:`SecurityHook.java`、`MemoryManager.java` 等。

### `String.repeat(int)`(JDK 11+)

```java
"\n".repeat(n);
"  ".repeat(depth);
```

出现位置:`MemoryManager.java`、`LLMMessageCompactor.java`、`SkillManager.java` 等。

### `Stream.toList()`(JDK 16+,但常被列为 JDK11+ 替代写法)

```java
list.stream().filter(...).toList();
```

出现位置:全项目约 20+ 处,如 `MemoryManager.java`、`ReactiveMessageCompactor.java`、`BudgetMessageCompactor.java`、`LLMMessageCompactor.java`、`SkillManager.java` 等。

### `Map.of(...)` / `List.of(...)` / `Set.of(...)`(JDK 9+)

```java
Map<String, String> m = Map.of("k", "v");
```

出现位置:`SkillManager.java`、`MemoryManager.java` 等。

### `Predicate.not(...)`(JDK 11+)

```java
list.stream().filter(Predicate.not(String::isBlank)).toList();
```

出现位置:`ReactiveMessageCompactor.java`、`LLMMessageCompactor.java`。

### `Pattern.asMatchPredicate()`(JDK 11+)

```java
Pattern.compile("...").asMatchPredicate()
```

出现位置:`SecurityHook.java`。

---

## JDK 15 特性

### Text Blocks(`"""..."""`)

```java
String template = """
        {
          "name": "%s",
          "description": "%s"
        }
        """;
```

出现位置:
- `src/main/java/com/vanilla/tool/EditFileTool.java`
- `src/main/java/com/vanilla/tool/WriteFileTool.java`
- `src/main/java/com/vanilla/tool/ReadFileTool.java`
- `src/main/java/com/vanilla/skill/SkillManager.java`
- `src/main/java/com/vanilla/memory/MemoryManager.java`
- `src/main/java/com/vanilla/compactor/LLMMessageCompactor.java`

---

## JDK 16 特性

### `instanceof` 模式匹配(JEP 394)

```java
if (obj instanceof Memory mem) {
    mem.name();
}
if (memory instanceof MemoryWrapper wrapper) {
    wrapper.memories();
}
```

出现位置:全项目约 10+ 处,如:
- `src/main/java/com/vanilla/memory/MemoryManager.java`(5 处)
- `src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java`
- `src/main/java/com/vanilla/compactor/BudgetMessageCompactor.java`
- `src/main/java/com/vanilla/compactor/LLMMessageCompactor.java`
- `src/main/java/com/vanilla/skill/SkillManager.java`
- `src/main/java/com/vanilla/hook/HookManager.java`
- `src/main/java/com/vanilla/hook/HookEngine.java`

### `record`(JEP 395)

```java
public record Memory(String name,
                     String description,
                     MemoryType type,
                     String body) { }

public record MemoryWrapper(List<Memory> memories) { }
```

出现位置:
- `src/main/java/com/vanilla/memory/Memory.java`
- `src/main/java/com/vanilla/memory/MemoryWrapper.java`

---

## JDK 17 特性

### Switch 表达式(JEP 361)

```java
String result = switch (type) {
    case MEMORY -> "memory";
    case SKILL  -> "skill";
    default     -> "unknown";
};
```

出现位置:
- `src/main/java/com/vanilla/memory/MemoryManager.java`
- `src/main/java/com/vanilla/compactor/ReactiveMessageCompactor.java`
- `src/main/java/com/vanilla/compactor/LLMMessageCompactor.java`
- `src/main/java/com/vanilla/skill/SkillManager.java`
- `src/main/java/com/vanilla/hook/HookManager.java`

---

## 总结

| JDK | 特性 | 主要使用点 |
| --- | --- | --- |
| **11** | `Files.readString/writeString`、`Path.of`、`String.isBlank/strip/repeat`、`Map.of/List.of/Set.of`、`Predicate.not`、`Pattern.asMatchPredicate` | I/O、字符串、集合工具类 |
| **15** | Text Blocks(`"""..."""`) | 工具类的提示词/模板生成 |
| **16** | `instanceof` 模式匹配、`record` | 类型分支(`MemoryManager`)、值对象(`Memory`、`MemoryWrapper`) |
| **17** | Switch 表达式(`case X ->`) | 类型/状态分支判断 |

### 回退到 JDK 8 的工作量评估

如果要将项目回退到 JDK 8,需改写:

1. **JDK 17 Switch 表达式** → 改为传统 `switch-case` 语句或 `if-else`(改动量较大)。
2. **`instanceof` 模式匹配** → 改为先 `instanceof` 再强转(改动量中等)。
3. **`record`** → 改为带 `getter`/`equals`/`hashCode`/`toString` 的普通 `final class`(改动量小但需重写)。
4. **Text Blocks** → 改为 `String` + `+` 拼接或 `String.format`(可读性下降)。
5. **`Files.readString/writeString`** → 改为 `new String(Files.readAllBytes(...))` / `Files.write(...)`。
6. **`Path.of`** → 改为 `Paths.get(...)`。
7. **`String.isBlank/strip/repeat`** → 改为 `trim().isEmpty()` / 手写 `replace` / 循环 `repeat`。
8. **`Stream.toList()`** → 改为 `.collect(Collectors.toList())`。
9. **`Map.of/List.of/Set.of`** → 改为 `Collections.unmodifiableMap(new HashMap<>())` 等。
10. **`Predicate.not`** → 改为 `.negate()`。