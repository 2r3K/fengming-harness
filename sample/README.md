# 测试用的真实导出包（不放也行）

`ExportParserTest` / `ExportWriterTest` 里有两个"真包"用例，需要一份 DeepSeek 官方导出的 zip：

```
deepseek_data-YYYY-MM-DD.zip
```

用法二选一：

1. **放这里**：把包重命名为 `sample/deepseek_data.zip`
2. **用环境变量**（推荐，别把个人数据放进仓库）：

   ```bash
   # macOS / Linux
   DEEPSEEK_EXPORT_ZIP=/path/to/deepseek_data-2026-11-21.zip ./gradlew testDebugUnitTest

   # Windows PowerShell
   $env:DEEPSEEK_EXPORT_ZIP="D:\path\to\deepseek_data-2026-11-21.zip"; .\gradlew.bat testDebugUnitTest
   ```

没提供时这两个用例会自动 **skip**（不算失败）。

> ⚠️ 真实的导出包里是你的私人聊天记录，**不要提交进 git**。`sample/*.zip` 已经在 `.gitignore` 里。
