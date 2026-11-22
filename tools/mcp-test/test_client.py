#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自测脚本：完整走一遍"握手 → 列工具 → 调工具"，顺便验一下安全拦截。

跑法（服务器开着的情况下，另开一个窗口）：
    python test_client.py
换端口/口令：python test_client.py 9000 你的口令
"""

import json
import sys
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "change-me-please"
URL = "http://127.0.0.1:%d/mcp" % PORT
SESSION = {"id": None}


def post(payload, expect_json=True, token=None, origin=None):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(URL, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json, text/event-stream")
    request.add_header("Authorization", "Bearer " + (TOKEN if token is None else token))
    if SESSION["id"]:
        request.add_header("Mcp-Session-Id", SESSION["id"])
    if origin:
        request.add_header("Origin", origin)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            session_id = response.headers.get("Mcp-Session-Id")
            if session_id:
                SESSION["id"] = session_id
            body = response.read().decode("utf-8")
            if not expect_json:
                return response.status, None
            return response.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as exc:
        return exc.code, None


def expect_denied(title, wanted_code, **kwargs):
    status, _ = post({"jsonrpc": "2.0", "id": 99, "method": "tools/list", "params": {}}, **kwargs)
    ok = status == wanted_code
    print("   %s %s（HTTP %s，期望 %s）" % ("OK" if ok else "!!", title, status, wanted_code))
    return ok


def main():
    print("连 %s" % URL)
    problems = 0

    print("")
    print("1) 握手 initialize")
    status, payload = post(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "fengming-selftest", "version": "0.9.2"},
            },
        }
    )
    result = (payload or {}).get("result") or {}
    print("   状态 %s，协议 %s，服务器 %s" % (status, result.get("protocolVersion"), result.get("serverInfo")))
    print("   会话 ID：%s" % SESSION["id"])
    if not SESSION["id"]:
        print("   !! 服务器没给 Mcp-Session-Id")
        problems += 1

    print("")
    print("2) 通知 initialized")
    status, _ = post({"jsonrpc": "2.0", "method": "notifications/initialized"}, expect_json=False)
    print("   状态 %s（应该是 202）" % status)

    print("")
    print("3) 列工具 tools/list")
    status, payload = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    tools = ((payload or {}).get("result") or {}).get("tools") or []
    for tool in tools:
        print("   - %s：%s" % (tool.get("name"), (tool.get("description") or "")[:40]))
    if not tools:
        print("   !! 一个工具都没列出来")
        return

    print("")
    print("4) 调工具 ping")
    status, payload = post(
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "ping", "arguments": {"text": "你好，我是手机"}},
        }
    )
    for item in (((payload or {}).get("result") or {}).get("content") or []):
        print("   → %s" % item.get("text"))

    print("")
    print("5) 调工具 add")
    status, payload = post(
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {"name": "add", "arguments": {"a": 1234, "b": 8}},
        }
    )
    for item in (((payload or {}).get("result") or {}).get("content") or []):
        print("   → %s" % item.get("text"))

    print("")
    print("6) 调一个不存在的工具（应该返回错误文字，不是崩）")
    status, payload = post(
        {
            "jsonrpc": "2.0",
            "id": 5,
            "method": "tools/call",
            "params": {"name": "nope", "arguments": {}},
        }
    )
    result = (payload or {}).get("result") or {}
    print("   → %s（isError=%s）" % (result.get("content"), result.get("isError")))

    print("")
    print("7) 安全：不带口令 → 应该 401")
    if not expect_denied("无口令请求", 401, token=""):
        problems += 1

    print("")
    print("8) 安全：口令错 → 应该 401")
    if not expect_denied("错口令请求", 401, token="wrong-token"):
        problems += 1

    print("")
    print("9) 安全：伪装成浏览器（带 Origin）→ 应该 403")
    if not expect_denied("浏览器请求", 403, origin="https://evil.example"):
        problems += 1

    print("")
    if problems == 0:
        print("全部通过 OK")
    else:
        print("有 %d 项没过，看上面的 !!" % problems)


if __name__ == "__main__":
    main()
