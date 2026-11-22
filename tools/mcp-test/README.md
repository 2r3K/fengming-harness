# DeepSeek奉明 · 测试用 MCP 服务器

这是给你电脑用的最小 MCP 服务器，只暴露两个测试工具，用来验证手机 App 能不能连上并调用。

**零依赖**：只用 Python 自带库，不用 `pip install` 任何东西。

**带安全防护**（v0.2.0 起）：
- 口令校验：默认口令 `change-me-please`，没口令一律 401
- 拒绝浏览器请求：带 `Origin` 头的请求一律 403（你电脑上打开的任何网页都调不动它）
- 不开放跨域、请求体上限 1MB、只认 4 个方法
- 自带 `safe_path()`，以后加"读文件"工具时用它锁死目录

---

## 一、启动

在 `tools/mcp-test` 目录里开一个 PowerShell 窗口：

```powershell
python server.py
```

看到这样的输出就成功了：

```
==============================================================
DeepSeek奉明 · 测试用 MCP 服务器
版本：0.2.0   工具：ping、add
端口：8765
口令校验：已开启（手机里「令牌」要填这个口令）
--------------------------------------------------------------
手机里填这个地址： http://192.168.1.10:8765/mcp
--------------------------------------------------------------
```

**把那一行地址抄下来**（每台电脑不一样，手机热点换了 IP 也会变）。
**口令是 `change-me-please`，手机里「令牌」那栏必须填它，否则连不上。**

> 示例端口用 **8765**（避开常被别的软件占用的 8000）。
> 想换端口：`python server.py 9000`
> 想换口令：`python server.py 8765 你的新口令`
> 临时不用口令：`python server.py 8765 ""`（只在自己完全信任的网络里这么干）

## 二、先自测（可选，但建议做）

**另开一个** PowerShell 窗口（服务器那个窗口别关）：

```powershell
cd 你放这个仓库的目录\tools\mcp-test
python test_client.py
```

全绿说明服务器本身没问题：

```
1) 握手 initialize          → 协议 2025-06-18
2) 通知 initialized         → 202
3) 列工具 tools/list        → ping、add
4) 调工具 ping              → 收到你的消息：「你好，我是手机」
5) 调工具 add               → 1234 + 8 = 1242
6) 调不存在的工具           → 返回错误文字，不崩
```

## 三、手机端配置

1. 手机和电脑连**同一个 WiFi**（你现在电脑连手机热点，那就已经在同一个网里了）
2. App → 设置 → 「MCP 工具服务器」→ 右上角「添加」
3. 名字随便填（例如 `我的电脑`），地址填上一步抄下来的那一行，**令牌填 `change-me-please`**
4. 点「测试连接」，应该显示：`✅ fengming-pc 0.2.0 · 2 个工具：ping、add`
5. 回聊天页，打开 **🔧 工具** 开关
6. 问它：「用电脑上的 ping 工具跟我打个招呼」

会弹窗问你「模型想用『我的电脑 · ping』」，点允许，然后回答里会带上电脑返回的文字。

## 四、连不上怎么办

**手机测试连接报"连不上/超时"**，按顺序排查：

1. **防火墙**：第一次运行 Python 会弹窗，要勾「专用网络」再点允许。
   如果当时点了取消，用管理员 PowerShell 手动加一条：
   ```powershell
   netsh advfirewall firewall add rule name="FengmingMCP" dir=in action=allow protocol=TCP localport=8765
   ```

2. **IP 对不对**：电脑上运行 `ipconfig`，找「无线局域网适配器」那一栏的 IPv4 地址，
   和服务器启动时打印的是不是同一个。热点重连后 IP 可能会变，重新抄一遍。

3. **是不是同一个网**：手机浏览器打开 `http://电脑IP:8765/mcp`，
   如果能看到一段 JSON（提示用 POST），说明网络通了，问题在 App 那边；
   打不开就是网络/防火墙的问题。

4. **端口被占**：启动时报 `Address already in use`，换个端口：
   `python server.py 9000`，手机地址也改成 9000。

## 五、加自己的工具

在 `server.py` 里照着 `ping` 抄两段就行，改一处 `TOOLS`、加一个函数：

```python
TOOLS["my_tool"] = {
    "name": "my_tool",
    "description": "这个工具干什么用的（模型靠这句话决定要不要调用）",
    "inputSchema": {
        "type": "object",
        "properties": {"arg1": {"type": "string", "description": "参数说明"}},
        "required": ["arg1"],
    },
}

def call_my_tool(args):
    return "结果：" + str(args.get("arg1"))

HANDLERS["my_tool"] = call_my_tool
```

改完重启服务器（Ctrl+C 再 `python server.py`），手机里重新点一次「测试连接」就能看到新工具。

## 六、想要"官方版"

如果你以后想用官方的 MCP Python SDK（功能全，但要装包）：

```powershell
pip install "mcp[cli]"
```

```python
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("fengming-pc", host="0.0.0.0", port=8765)

@mcp.tool()
def ping(text: str) -> str:
    """回声工具：把文字原样传回来。"""
    return "收到：" + text

if __name__ == "__main__":
    mcp.run(transport="streamable-http")
```

这个 App 两种都能连，因为走的都是同一套 streamable-http 协议。

---

## 七、安全（重要，加工具之前先看）

### 现在的三道闸

1. **口令**：默认 `change-me-please`（只为方便你本地试，别拿它连公共网络）。
   换掉：`python server.py 8765 新口令`，或者启动前设环境变量 `MCP_TOKEN`，手机里也要改。
   用了默认口令时，启动会打印一行 ⚠ 提醒。
2. **拒绝浏览器**：带 `Origin` 头的请求一律 403。这样你在这台电脑上打开的任何网页都调不动它。
3. **手机端每次弹窗**：模型想调工具，你必须点「允许」。会改动东西的工具（名字/说明里带 删/写/执行/关机/上传… 这类词）**永远不能"以后不用问"**，而且弹窗是红字警告。

### 加"读文件"类工具时，必须锁死目录

**共享目录在哪儿**：默认是 `tools/mcp-test/shared/`（脚本旁边的空文件夹，可以自己建）。
想指向你真正放资料的目录（比如 `D:\笔记`），设一个环境变量再启动：

```powershell
$env:MCP_ROOT = "D:\笔记"
python server.py
```

绝对不要写 `read_any_file(path)`。用文件里已经写好的 `safe_path()`：

```python
def call_read_note(args):
    relative = str(args.get("path", ""))
    full = safe_path(relative)          # 越界会直接抛 PermissionError
    if not os.path.isfile(full):
        raise ValueError("没有这个文件")
    with open(full, "r", encoding="utf-8") as handle:
        return handle.read()[:4000]     # 一定要截断，别把整本书塞进对话
```

为什么不能只用 `path.startswith(...)`：

| 绕过写法 | startswith 判定 | realpath + commonpath |
|---|---|---|
| `..\..\Windows\System32\drivers\etc\hosts` | 通过（前缀对） | **拦住** |
| `<共享目录>evil\x` | 通过（前缀对） | **拦住** |
| 指向外部的符号链接 | 通过（前缀对） | **拦住** |

### 另外两条铁律

- **用完了就关**（Ctrl+C）。它没有开机自启，关了就是彻底关了。
- **工具返回的每一个字都会发到 DeepSeek 的服务器**（进对话上下文）。别让它读密码本、身份证照片、密钥文件。
- 永远不要在路由器上给 8765 做端口转发。这套是明文 HTTP，只能在家里/自己的热点里用。
