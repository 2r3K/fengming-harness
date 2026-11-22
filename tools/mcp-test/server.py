#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeepSeek奉明 · 最小 MCP 服务器（streamable-http 传输，零依赖，带口令校验）

只做一件事：让手机上的 App 通过局域网连过来，能查到工具并调用。

安全设计（这几条别删）：
    1. 口令校验：默认口令 change-me-please，没带对的口令一律 401。
       改口令：python server.py 8765 你的新口令
       临时关掉：python server.py 8765 ""      (只在自己完全信任的网络里这么干)
    2. 拒绝浏览器请求：带 Origin / Sec-Fetch-Site 头的一律 403。
       这样你在这台电脑上打开的任何网页，都没法偷偷调用这些工具。
    3. 不开放跨域（没有 Access-Control-Allow-Origin），浏览器 fetch 拿不到响应。
    4. 请求体上限 1MB，防本地垃圾请求把内存打爆。
    5. 只暴露 initialize / tools/list / tools/call / ping 四个方法，别的直接报错。
    6. safe_path()：以后加"读文件"类工具，必须用它锁死目录
       （目录 = 脚本旁边的 shared/，或者环境变量 MCP_ROOT 指定的地方）。

口令来源（三选一，后面的覆盖前面的）：
    python server.py <端口> <口令>      ← 命令行参数
    MCP_TOKEN=<口令> python server.py   ← 环境变量
    （都不给就用默认口令 change-me-please，仅适合自己家里临时试）

运行：
    python server.py

手机那边填：
    http://<电脑的局域网IP>:8765/mcp
    「令牌」那栏填：change-me-please

自测（另开一个窗口）：
    python test_client.py
"""

import hmac
import json
import os
import socket
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- 配置

SERVER_NAME = "fengming-pc"
SERVER_VERSION = "0.2.0"
PROTOCOL_VERSION = "2025-06-18"

DEFAULT_TOKEN = "change-me-please"
AUTH_TOKEN = DEFAULT_TOKEN

# 以后加"读文件"类工具时，只允许碰这个目录。
# 默认是脚本旁边的 shared/ 文件夹；想换成你自己放资料的目录，设环境变量 MCP_ROOT。
ALLOWED_ROOT = os.path.realpath(
    os.environ.get("MCP_ROOT")
    or os.path.join(os.path.dirname(os.path.abspath(__file__)), "shared")
)

MAX_BODY_BYTES = 1024 * 1024  # 1MB

# ---------------------------------------------------------------- 路径安全

def safe_path(user_part: str) -> str:
    """
    模型/用户给的那半截路径，只允许落在 ALLOWED_ROOT 里面。
    别用 startswith：'<共享目录>evil' 也能通过前缀检查。
    realpath 会展开 '..' 和符号链接，commonpath 才认真正的目录边界。
    """
    target = os.path.realpath(os.path.join(ALLOWED_ROOT, user_part))
    root = os.path.normcase(ALLOWED_ROOT)
    real = os.path.normcase(target)
    try:
        inside = os.path.commonpath([root, real]) == root
    except ValueError:
        inside = False  # 不同盘符之类
    if not inside:
        raise PermissionError("路径越界，拒绝访问")
    return target


# ---------------------------------------------------------------- 工具定义

TOOLS = {
    "ping": {
        "name": "ping",
        "description": "回声工具：把你给的一段文字原样传回来，并附上电脑当前时间。用来确认手机和电脑之间的链路是通的。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "随便一段文字，例如 你好"},
            },
            "required": ["text"],
        },
    },
    "add": {
        "name": "add",
        "description": "加法工具：算 a + b。用来确认参数能正确传到电脑上。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "a": {"type": "number", "description": "第一个加数"},
                "b": {"type": "number", "description": "第二个加数"},
            },
            "required": ["a", "b"],
        },
    },
}


def call_ping(args):
    text = str(args.get("text", ""))[:2000]
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    return "收到你的消息：「%s」\n（这条是电脑回给你的，电脑时间 %s）" % (text, now)


def call_add(args):
    a = args.get("a", 0)
    b = args.get("b", 0)
    try:
        a_num = float(a)
        b_num = float(b)
    except (TypeError, ValueError):
        raise ValueError("a 和 b 都得是数字，收到的是 %r 和 %r" % (a, b))
    total = a_num + b_num
    if total == int(total):
        total = int(total)
    return "%s + %s = %s" % (a, b, total)


HANDLERS = {"ping": call_ping, "add": call_add}


def handle_tools_call(params):
    name = params.get("name") or ""
    args = params.get("arguments") or {}
    if not isinstance(args, dict):
        return {"content": [{"type": "text", "text": "arguments 必须是对象"}], "isError": True}
    if name not in HANDLERS:
        known = "、".join(TOOLS.keys())
        return {
            "content": [{"type": "text", "text": "没有这个工具：%s（我有：%s）" % (name, known)}],
            "isError": True,
        }
    try:
        text = HANDLERS[name](args)
        return {"content": [{"type": "text", "text": text}], "isError": False}
    except Exception as exc:
        return {"content": [{"type": "text", "text": "工具出错了：%s" % exc}], "isError": True}


def dispatch(method, params):
    """返回 (result, error)；两个只有一个非 None"""
    if method == "initialize":
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        }, None

    if method == "ping":
        return {}, None

    if method == "tools/list":
        return {"tools": list(TOOLS.values())}, None

    if method == "tools/call":
        return handle_tools_call(params), None

    return None, {"code": -32601, "message": "不支持的方法：%s" % method}


# ---------------------------------------------------------------- HTTP 层

SESSIONS = set()


class Handler(BaseHTTPRequestHandler):
    server_version = "FengmingMCP/" + SERVER_VERSION
    protocol_version = "HTTP/1.1"

    # ---------- 发送 ----------

    def _send_json(self, payload, status=200, extra_headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        # 故意不发 Access-Control-Allow-Origin：不给浏览器跨域调用的机会
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _send_empty(self, status=202):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _reject(self, status, message, extra_headers=None):
        self._send_json(
            {"jsonrpc": "2.0", "id": None, "error": {"code": -32000, "message": message}},
            status=status,
            extra_headers=extra_headers,
        )

    def _log(self, note):
        stamp = time.strftime("%H:%M:%S")
        client = "%s:%s" % self.client_address[:2]
        print("[%s] %s %s" % (stamp, client, note), flush=True)

    # ---------- 校验 ----------

    def _from_browser(self):
        # 浏览器发跨域请求一定会带 Origin；Sec-Fetch-Site 是新一代浏览器的标记
        return bool(self.headers.get("Origin")) or bool(self.headers.get("Sec-Fetch-Site"))

    def _authorized(self):
        if not AUTH_TOKEN:
            return True
        header = (self.headers.get("Authorization") or "").strip()
        if not header.startswith("Bearer "):
            return False
        return hmac.compare_digest(header[7:].strip(), AUTH_TOKEN)

    # ---------- 请求 ----------

    def do_OPTIONS(self):
        # 不给预检通过：浏览器那边会直接失败
        self._log("拒绝：浏览器的预检请求")
        self._reject(403, "browser preflight is not allowed")

    def do_GET(self):
        self._log("GET %s（本服务器不提供常驻事件流）" % self.path)
        self._reject(405, "这个服务器不用 GET 长连接，请用 POST")

    def do_DELETE(self):
        if self._from_browser() or not self._authorized():
            self._reject(401 if not self._authorized() else 403, "unauthorized")
            return
        session_id = self.headers.get("Mcp-Session-Id")
        SESSIONS.discard(session_id)
        self._log("DELETE 会话结束 %s" % session_id)
        self._send_empty(200)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            self._log("拒绝：请求体太大（%d 字节）" % length)
            self._reject(413, "body too large")
            return
        raw = self.rfile.read(length) if length > 0 else b""

        # 先挡浏览器，再验口令；两道都过才处理
        if self._from_browser():
            self._log("拒绝：来自浏览器的请求")
            self._reject(403, "browser requests are not allowed")
            return
        if not self._authorized():
            self._log("拒绝：口令不对或没带口令")
            self._reject(
                401,
                "unauthorized: 服务器要求口令，请在手机 MCP 设置里填「令牌」",
                extra_headers={"WWW-Authenticate": "Bearer"},
            )
            return

        try:
            message = json.loads(raw.decode("utf-8"))
            if not isinstance(message, dict):
                raise ValueError("not an object")
        except Exception:
            self._log("收到无法解析的请求体")
            self._reject(400, "不是合法的 JSON-RPC 报文")
            return

        method = message.get("method") or ""
        params = message.get("params") or {}
        if not isinstance(params, dict):
            params = {}
        request_id = message.get("id", None)
        is_notification = "id" not in message

        if is_notification:
            self._log("通知 %s" % method)
            self._send_empty(202)
            return

        self._log("请求 %s" % method)
        result, error = dispatch(method, params)

        headers = {}
        if method == "initialize":
            session_id = uuid.uuid4().hex
            SESSIONS.add(session_id)
            headers["Mcp-Session-Id"] = session_id
            headers["MCP-Protocol-Version"] = PROTOCOL_VERSION

        if error is not None:
            payload = {"jsonrpc": "2.0", "id": request_id, "error": error}
        else:
            payload = {"jsonrpc": "2.0", "id": request_id, "result": result}

        self._send_json(payload, extra_headers=headers)

    def log_message(self, fmt, *args):
        pass  # 自己打日志，别让默认的每行噪音刷屏


# ---------------------------------------------------------------- 启动

def local_ips():
    ips = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        ips.append(probe.getsockname()[0])
        probe.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips


def main():
    global AUTH_TOKEN

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    if len(sys.argv) > 2:
        AUTH_TOKEN = sys.argv[2].strip()
    else:
        AUTH_TOKEN = os.environ.get("MCP_TOKEN", DEFAULT_TOKEN).strip()

    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)

    print("=" * 62)
    print("DeepSeek奉明 · 测试用 MCP 服务器")
    print("版本：%s   工具：%s" % (SERVER_VERSION, "、".join(TOOLS.keys())))
    print("端口：%d" % port)
    print("口令校验：%s" % ("已开启（手机里「令牌」要填这个口令）" if AUTH_TOKEN else "已关闭 —— 不推荐"))
    if AUTH_TOKEN == DEFAULT_TOKEN:
        print("⚠ 正在用默认口令 change-me-please：只适合自己家里试。")
        print("  换掉：python server.py %d 你的新口令" % port)
    print("-" * 62)
    ips = local_ips()
    if ips:
        for ip in ips:
            print("手机里填这个地址： http://%s:%d/mcp" % (ip, port))
    else:
        print("没查到局域网 IP，用 ipconfig 看一下你的 IPv4 地址")
    print("-" * 62)
    print("只接受手机 App 的请求：浏览器来的（带 Origin 头）一律拒绝")
    print("手机和电脑要连同一个 WiFi（电脑连手机热点也算同一个网）")
    print("第一次运行 Windows 会弹防火墙提示：勾上「专用网络」再允许")
    print("停止：按 Ctrl+C")
    print("=" * 62)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()
