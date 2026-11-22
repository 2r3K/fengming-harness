# Fengming Harness (奉明Harness)

**A DeepSeek client for Android with your own API key — MCP tool calling, local knowledge-base RAG, token cost control, and official-format import/export.**

Single-module app written in Kotlin + Jetpack Compose. No backend, no account system, no telemetry — every conversation lives in a local SQLite file on your phone.

> ⚠️ **Unofficial project.** Not affiliated with DeepSeek in any way; it only calls DeepSeek's public API.
> "DeepSeek" is a trademark of its owner. API usage costs are yours to pay.

[中文说明](README.md) · [Architecture](ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md)

---

## What makes it different

| | Official app | Fengming Harness |
|---|---|---|
| Credentials | Official account | **Your own API key** |
| History | Cloud | **On device only** |
| Tools | Built in | **MCP over HTTP** — connect tools on your own PC |
| Knowledge base | Built in | **Local RAG** over a folder on your phone |
| Cost | Website | **Per-message estimate + live account balance** |
| Data portability | Export only | **Official-format import/export**, full backup incl. images |

## Features

**Chat** — SSE streaming, stop / continue, collapsible reasoning ("thinking") blocks, web search with a source list, image input (camera / gallery / share sheet), document parsing (PDF, Office, plain text).

**Local RAG** — pick a folder as your knowledge base, keyword retrieval, optional document summarisation to save tokens, automatic folding of old turns (summarised, not dropped).

**MCP tools** — standard streamable-HTTP MCP servers, visible tool-call panel, and dangerous tools (delete / write / exec) **always require explicit approval**. A zero-dependency test server ships in `tools/mcp-test/`.

**Money & data** — per-message token/cost, monthly and daily totals, **live official account balance** (the endpoint is free), import of official DeepSeek exports (zip, including branches, reasoning, search sources and images), export back to the **official format**, full backup (database + images in one zip), restore, and a storage audit with orphan-image cleanup.

**Other** — dark mode, three font sizes, voice input, read-aloud, full-text search, prompt templates.

## Screenshots

> Coming soon — see [`docs/screenshots/README.md`](docs/screenshots/README.md).

## Build

Requirements: JDK 17+ (21 recommended), Android SDK with `compileSdk 37`, `minSdk 26`.

```bash
git clone https://github.com/2r3K/fengming-harness.git
cd fengming-harness
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Optional release signing:

```bash
cp keystore.properties.example keystore.properties
# fill in your own keystore details; without it the build still works, just unsigned for release
```

## Usage

1. Install, open **Settings** (top right)
2. Enter your **API key** from [platform.deepseek.com](https://platform.deepseek.com/)
3. Keep `https://api.deepseek.com` unless you use a compatible gateway
4. Start chatting

## Tests

```bash
./gradlew testDebugUnitTest
```

19 unit tests covering the official-format parser, the official-format writer (including a full round trip) and the backup archive (including path-traversal attack samples). Two cases need a real DeepSeek export and are skipped unless you provide one:

```bash
DEEPSEEK_EXPORT_ZIP=/path/to/deepseek_data-YYYY-MM-DD.zip ./gradlew testDebugUnitTest
```

## Known limitations

- Very long conversations (1000+ messages) are not virtualised yet and may scroll poorly
- MCP tool results are treated as plain text
- Voice input / TTS depend on system services and vary by device
- The exported official-format zip **cannot be imported back into the official app** (DeepSeek has no import entry point); it targets this project and other tools that read that format
- Cost figures are estimates from server-reported tokens × price, not an official invoice

## License

[MIT](LICENSE) © 2026 2r3K

Use it, modify it, ship it commercially — just keep the copyright notice. Please don't present it as the official client.
