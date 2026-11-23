# 奉明Harness

**一个 Android 上的 DeepSeek 客户端：自带 API Key，支持 MCP 工具调用、本地知识库 RAG、Token 计费控制、官方格式导入导出。**

Kotlin + Jetpack Compose 写的单模块 App，没有后端、没有账号体系、不收集任何数据 —— 所有聊天记录都存在你手机本地的 SQLite 里。

> ⚠️ **非官方项目**：本项目与 DeepSeek 官方没有任何关系，只是调用 DeepSeek 公开的 API。
> "DeepSeek" 是其权利人的商标，本项目不使用、不冒充官方品牌。
> 使用本 App 产生的 API 费用由你自己承担。

[English](README.en.md) · [架构说明](ARCHITECTURE.md) · [如何贡献](CONTRIBUTING.md)

---

## 它和官方 App 有什么不一样

| | 官方 App | 奉明Harness |
|---|---|---|
| API Key | 用官方账号登录 | **填自己的 Key**，你自己说了算 |
| 聊天记录 | 存云端 | **只存本机**，不联网不上传 |
| 工具调用 | 官方内置 | **MCP 协议**，能连你自己电脑上的工具 |
| 知识库 | 官方内置 | **本地 RAG**，直接读你手机里的文件夹 |
| 花费 | 看官网 | **本机逐条估算 + 官方余额实时查询** |
| 数据搬家 | 只能导出 | **官方格式导入/导出**、本机备份（含图片） |

## 功能

**对话**
- 流式输出（SSE），随时停止、断点续答（"继续生成"）
- 深度思考过程折叠显示（💭 点开看）
- 联网搜索开关，回答下面列出「来源（N）」，带站点和摘要
- 图片识别（拍照 / 相册 / 分享进来）
- 文档解析（PDF / Word / Excel / PPT / 纯文本），可选"连图一起看"或"只要文字"

**知识库（本地 RAG）**
- 选一个手机文件夹当知识库，按关键词召回相关片段
- 文档太大可以压成摘要，省 token
- 长对话自动折叠最早的内容（摘要而不是丢弃）

**工具（MCP）**
- 连标准的 MCP streamable-HTTP 服务器
- 工具调用面板可见，危险操作（删/写/执行类）**永远要你点确认**
- 附带一个零依赖的测试服务器：`tools/mcp-test/`

**钱与数据**
- 每条回答显示 token 和估算花费，按月/按天统计
- **官方账户余额**实时查询（不花 token）
- 导入 DeepSeek 官方导出的历史（zip，支持分支/思考/搜索来源/图片）
- 导出成**官方格式**（能被本项目、也能被其他读得懂该格式的工具读）
- 本机备份（数据库 + 图片打成一个 zip）、恢复、存储体检与孤儿图片清理

**其它**
- 深色模式、三档字号
- 语音输入、朗读
- 全文搜索、提示词模板、会话管理

## 截图

> 截图待补。放图位置见 [`docs/screenshots/README.md`](docs/screenshots/README.md)。

## 自己编译

**要求**

- JDK 25（`gradle/gradle-daemon-jvm.properties` 里钉的就是 25；别的版本 Gradle 会去下载工具链）
- Android SDK：`compileSdk 37` / `minSdk 26`（Android 8.0+）
  - 平台包名是 `platforms;android-37.0`（带小版本号），`build-tools;36.0.0`
- Gradle 由 wrapper 自带，不用另装

```bash
git clone https://github.com/2r3K/fengming-harness.git
cd fengming-harness
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**想打正式签名的包**（可选）

```bash
cp keystore.properties.example keystore.properties
# 然后按里面的注释填你自己的证书；没有这个文件也能编译，只是没有正式签名
```

## 用起来

1. 装好 App，右上角「设置」
2. 填 **API Key**（去 [DeepSeek 开放平台](https://platform.deepseek.com/) 申请，需要账户里有余额）
3. Base URL 默认 `https://api.deepseek.com`，用官方就保持不动
4. 回聊天页，问点什么

想连 MCP 工具的话：先在自己电脑上跑一个 MCP 服务器（比如 `tools/mcp-test/server.py`），
然后在 App 的「设置 → MCP 工具服务器」里填 `http://<你电脑的局域网IP>:8765/mcp` 和令牌。

## 项目结构

```
app/src/main/java/io/github/r2r3k/fengmingharness/
├── MainActivity.kt            入口（字号/主题加载、启动小扫除、分享进来的文件）
├── core/
│   ├── model/                 数据模型（ChatMsg / AppSettings / SourceItem …）
│   ├── provider/              DeepSeek API 客户端（SSE 流式、工具调用、错误映射）
│   ├── tool/                  本地工具（时间/算力/知识库检索）+ 危险操作判定
│   ├── mcp/                   MCP 客户端（JSON-RPC、会话、工具缓存）
│   ├── kb/                    本地知识库检索（RAG）
│   ├── importer/              官方导出解析（纯 Kotlin，可单测）
│   ├── exporter/              官方格式导出（纯 Kotlin，可单测）
│   ├── store/                 SQLite、备份打包、存储体检
│   ├── util/                  图片处理、价格、联网防护、余额查询
│   └── audio/                 语音输入、朗读
├── service/ChatService.kt     前台服务（后台不被冷冻）
└── ui/                        Compose 界面（chat / settings / bill / diag / importer …）
```

更多设计取舍见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 测试

```bash
./gradlew testDebugUnitTest
```

- `ExportParserTest`（11 个）：DeepSeek 官方导出包的解析，含各种坏数据/未知字段
- `ExportWriterTest`（3 个）：官方格式导出，含「真包 → 解析 → 再导出 → 再解析」往返比对
- `BackupArchiveTest`（5 个）：备份包读写，含路径穿越攻击样本

其中两个"真包"用例需要一个真实的官方导出包才会跑，没提供时自动跳过：

```bash
DEEPSEEK_EXPORT_ZIP=/path/to/deepseek_data-YYYY-MM-DD.zip ./gradlew testDebugUnitTest
```

## 已知限制

- 超长对话（1000 条以上）滚动时未做虚拟化优化，可能不够滑
- MCP 工具的返回目前只当纯文本处理
- 语音输入/朗读依赖系统 TTS 和识别服务，不同机型表现不一样
- 导出的官方格式 zip **不能导回官方 App**（官方没有导入入口），它是给本项目和别的工具用的
- 估算是按服务端返回的 token × 单价算的，和官方账单可能差几分钱（以官网为准）

## 路线图

- [ ] 长对话的懒渲染
- [ ] MCP 多服务器分组开关
- [ ] 结构化工具结果（图片/表格）
- [ ] 英文界面

## 许可

[MIT](LICENSE) © 2026 2r3K

你可以自由使用、修改、商用；保留版权声明即可。**但请不要冒充官方发布**，也别把这个项目的名字当成你的招牌。
