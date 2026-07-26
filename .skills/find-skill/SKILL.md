description: 帮助在项目中查找文件、代码片段、符号定义和搜索文本内容。结合 glob、bash（grep/find）和 read_file 工具，高效定位代码。

# Find Skill 使用指南

本 skill 提供项目中"查找"类任务的最佳实践与工具组合方案。

## 适用场景

- 查找某个文件名或某类文件
- 在代码中搜索关键字、函数名、类名、字符串
- 定位符号（类、方法、字段）的定义位置
- 查找最近修改过的文件
- 在大量文件中按模式筛选

## 推荐工具组合

### 1. 按文件名/路径查找 → 用 `glob`
适合：已知扩展名或路径片段。
```
glob(pattern="**/*.java")
glob(pattern="src/main/**/User*.java")
glob(pattern="**/config*.yml", path=".")
```

### 2. 按内容/正则查找 → 用 `bash` + grep
适合：跨文件搜索关键字或代码片段。
```
grep -rn "TODO" src/
grep -rn --include="*.java" "class\s+UserService" .
grep -rn "public.*save" --include="*.java" .
```

### 3. 查找文件元数据（大小/修改时间/类型）→ 用 `bash`
```
find . -name "*.log" -size +10M
find . -mtime -7 -type f
```

### 4. 定位符号定义 → `grep` 配合行号 + `read_file` 精读
```
grep -n "public class\s\+\w\+" src/main/java/**/*.java
```

### 5. 在 Windows 环境注意事项
- 默认 shell 优先尝试 cmd.exe；可用 `shell="powershell"` 或 `shell="bash"` 显式指定
- 若出现乱码，cmd 下加 `chcp 65001>nul && ` 前缀
- 路径含空格时务必加双引号

## 工作流建议

1. **先粗后精**：先用 `glob` 缩小范围，再用 `grep` 在结果内搜索关键字
2. **并行调用**：多个独立的查找任务放同一个 function_calls 块中并发执行
3. **限定范围**：尽量提供 `path` 或 `--include`/`--exclude`，避免全盘扫描
4. **结果记录**：把命中的文件路径与行号记下来，后续用 `read_file`（带 offset/limit）精读
5. **必要时二次过滤**：第一次结果太多时，再用更具体的 pattern 二次筛选

## 常用 grep 选项速查

| 选项 | 作用 |
|------|------|
| `-r` | 递归 |
| `-n` | 显示行号 |
| `-i` | 忽略大小写 |
| `-l` | 只列出文件名 |
| `-w` | 全词匹配 |
| `-c` | 统计匹配数 |
| `--include=GLOB` | 仅搜索匹配 GLOB 的文件 |
| `--exclude=GLOB` | 排除匹配 GLOB 的文件 |
| `-E` | 启用扩展正则 |
| `-A n` / `-B n` | 显示匹配后/前 n 行 |

## 输出示例

当用户问"找一下所有 Controller 类"时：
1. `grep -rn --include="*.java" "class\s\+\w\+Controller" src/`
2. 若结果过多，加 `-l` 仅看文件列表，或加 `-E "class\s+(public\s+)?(final\s+)?class\s+\w+Controller"` 精确化
3. 把文件路径交给 `read_file` 读取关键部分