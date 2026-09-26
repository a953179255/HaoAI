#!/usr/bin/env python3
"""开发用的假 OpenAI 兼容服务 —— 用来在没有可用模型密钥时端到端验证 PC 端。

它真的走 HTTP + SSE，所以能覆盖：流式解析、tool_calls 分片拼装、Engine 回合循环、
工具执行、审批闸、溢出落库、会话持久化、Web 前端的事件流。**唯一没覆盖的是模型本身的质量。**

    python pc/tools/mock-openai.py --port 8099 --mode spill
    haoai-pc set base=http://127.0.0.1:8099/v1 model=mock
    haoai-pc task "跑一条长输出命令"

mode:
  chat    只回一段文本（含 markdown / 代码块，用来验前端渲染）
  tools   先调 write+edit+read，再收尾（验多回合与真文件）
  spill   跑一条 >16k 字符的 shell（验溢出落文件）
  ask     触发审批 + ask_user（验人机回合）
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE = "tools"
STATE = {"tool_rounds": 0}


def sse(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


def delta_chunks(text, calls=None):
    """把一次回合拆成多个 SSE 分片，故意把 arguments 切碎，模拟真实网关。"""
    out = []
    step = 14
    for i in range(0, max(len(text), 1), step):
        piece = text[i:i + step]
        d = {"role": "assistant"} if i == 0 else {}
        if piece:
            d["content"] = piece
        out.append({"choices": [{"delta": d, "index": 0, "finish_reason": None}]})
    if calls:
        for idx, c in enumerate(calls):
            args = c["arguments"]
            head = json.dumps(
                {"choices": [{"delta": {"tool_calls": [{"index": idx, "id": c["id"], "type": "function",
                                                       "function": {"name": c["name"], "arguments": ""}}]},
                              "index": 0, "finish_reason": None}]}, ensure_ascii=False)
            out.append(json.loads(head))
            for i in range(0, len(args), 9):
                frag = json.dumps(
                    {"choices": [{"delta": {"tool_calls": [{"index": idx, "function": {"arguments": args[i:i + 9]}}]},
                                  "index": 0, "finish_reason": None}]}, ensure_ascii=False)
                out.append(json.loads(frag))
        out.append({"choices": [{"delta": {}, "index": 0, "finish_reason": "tool_calls"}]})
    else:
        out.append({"choices": [{"delta": {}, "index": 0, "finish_reason": "stop"}]})
    return out


PLAN = {
    "chat": [
        ("这是 mock 模型的最终回答。\n\n## 小结\n- 工具链路与流式解析都跑通了\n- `inline code` 与代码块应该被前端渲染\n\n```python\nprint('hello from haoai-pc')\n```\n", None),
    ],
    "tools": [
        ("我先建文件", [{"id": "call_1", "name": "todo",
                        "arguments": json.dumps({"items": [{"text": "建 hello.txt", "status": "doing"},
                                                            {"text": "改第一行", "status": "pending"},
                                                            {"text": "读回来核对", "status": "pending"}]})}]),
        ("写入", [{"id": "call_2", "name": "write",
                   "arguments": json.dumps({"path": "hello.txt", "content": "first line\nsecond line\n"})}]),
        ("改一行", [{"id": "call_3", "name": "edit",
                     "arguments": json.dumps({"path": "hello.txt", "old_string": "first line",
                                              "new_string": "hello from HaoAI PC"})}]),
        ("读回来核对", [{"id": "call_4", "name": "read", "arguments": json.dumps({"path": "hello.txt"})}]),
        ("做完了：hello.txt 的第一行已改成 `hello from HaoAI PC`，第二行保持 `second line`。"
         "我是真读过文件才这么说的（上面 read 的结果就是证据）。", None),
    ],
    "spill": [
        ("跑一条长输出", [{"id": "call_s", "name": "shell",
                           "arguments": json.dumps({"command": "seq 1 8000",
                                                    "shell": "bash", "timeout": 120})}]),
        ("这条命令输出 4 万字符，会话里只留了头尾摘要，完整内容在 `.haoai-output/` 里，"
         "需要中间部分我可以用 read 分段回读。", None),
    ],
    "git": [
        ("先看看仓库现在什么样", [{"id": "g1", "name": "git",
                                   "arguments": json.dumps({"sub": "status", "args": "--porcelain"})}]),
        ("读一下要改的文件", [{"id": "g2", "name": "read",
                               "arguments": json.dumps({"path": "notes.md"})}]),
        ("改一行", [{"id": "g3", "name": "edit",
                     "arguments": json.dumps({"path": "notes.md", "old_string": "TODO 待补",
                                              "new_string": "已完成：PC 端 git 工具链路验证通过"})}]),
        ("看看 diff", [{"id": "g4", "name": "git",
                        "arguments": json.dumps({"sub": "diff", "args": "-- notes.md"})}]),
        ("提交", [{"id": "g5", "name": "git",
                   "arguments": json.dumps({"sub": "commit", "args": "-am \"docs: 更新 notes\""})}]),
        ("做完了。改动是 notes.md 里那一行 TODO 被换成验证结论，"
         "已经 commit（上面 git diff 与 commit 的输出就是证据）。"
         "没动别的文件，也没 push。", None),
    ],
    "ask": [
        ("先问一下", [{"id": "call_q", "name": "ask_user",
                       "arguments": json.dumps({"question": "要绿色还是蓝色主题？", "options": ["绿色", "蓝色"]})}]),
        ("要动文件了", [{"id": "call_w", "name": "write",
                         "arguments": json.dumps({"path": "theme.txt", "content": "green\n"})}]),
        ("好，按你说的写好了 theme.txt。", None),
    ],
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            body = json.dumps({"data": [{"id": "mock"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        req = json.loads(self.rfile.read(n) or b"{}")
        msgs = req.get("messages", [])
        tool_rounds = sum(1 for m in msgs if m.get("role") == "tool")
        plan = PLAN.get(MODE, PLAN["tools"])
        idx = min(tool_rounds, len(plan) - 1)
        text, calls = plan[idx]
        stream = req.get("stream", False)
        if not stream:
            body = json.dumps({
                "choices": [{"message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for ch in delta_chunks(text, calls):
            self.wfile.write(sse(ch))
            self.wfile.flush()
            time.sleep(0.01)
        self.wfile.write(sse({"usage": {"prompt_tokens": 1234 + 200 * idx, "completion_tokens": 87}}))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--mode", default="tools", choices=list(PLAN.keys()))
    a = ap.parse_args()
    global MODE
    MODE = a.mode
    print(f"mock openai on http://127.0.0.1:{a.port}/v1  mode={MODE}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
