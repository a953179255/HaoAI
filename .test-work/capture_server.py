#!/usr/bin/env python3
"""抓 HaoAI 真机请求：完整 body 落盘 + 回罐头 SSE。诊断 400 用。"""
import json, time
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = r"G:/工作台/HaoAI/.test-work/captured_req.json"

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        with open(LOG, "wb") as f:
            f.write(body)
        print(f"[{time.strftime('%H:%M:%S')}] captured {n}B -> {LOG} path={self.path}", flush=True)
        # 罐头 SSE：一次 tool_call（todo）然后结束
        sse = (
            'data: {"id":"cap1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_cap1","type":"function","function":{"name":"todo","arguments":"{\\\"todos\\\":[{\\\"text\\\":\\\"抓包诊断\\\",\\\"status\\\":\\\"completed\\\",\\\"priority\\\":\\\"medium\\\"}]}"}}]}}]}\n\n'
            'data: {"id":"cap1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}\n\n'
            "data: [DONE]\n\n"
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(sse.encode())

HTTPServer(("127.0.0.1", 8802), H).serve_forever()
