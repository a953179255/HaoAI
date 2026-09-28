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
  multi   三条会话各一份剧本（验多会话并行时事件不串台）
"""
import os
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE = "tools"
CLIP = os.environ.get("PRE_CLIP", "素材.mp4")
CODE_PY = "\n".join([
    'import os',
    'b = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c63000100000500010d0a252242600000000049454e44ae426082")',
    'here = os.path.dirname(os.path.abspath(__file__))',
    'open(os.path.join(here, "dot.png"), "wb").write(b)',
    'print("写了 dot.png 结果是", 6 * 7)',
])
STATE = {"tool_rounds": 0}


def sse(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


def delta_chunks(text, calls=None, reasoning=None):
    """把一次回合拆成多个 SSE 分片，故意把 arguments 切碎，模拟真实网关。

    reasoning 走的是 llama.cpp / 各家网关的 `delta.reasoning_content` 字段。
    之前这份假网关**从来没发过**这个字段，于是"思考链"那条链路（Provider 解析 →
    Engine 事件 → 前端的可折叠思考卡）在脱网验收里是零覆盖的 —— 界面看起来"没坏"
    只是因为卡片永远不出现。所以现在要能发。
    """
    out = []
    step = 14
    if reasoning:
        for i in range(0, len(reasoning), 12):
            out.append({"choices": [{"delta": {"reasoning_content": reasoning[i:i + 12]},
                                     "index": 0, "finish_reason": None}]})
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
        ("这是 mock 模型的最终回答。\n\n## 小结\n- 工具链路与流式解析都跑通了\n- `inline code` 与代码块应该被前端渲染\n\n```python\nprint('hello from haoai-pc')\n```\n",
         None,
         "先确认要答什么：这是一次验收，不需要动文件。\n然后想清楚证据从哪来 —— 只看代码不算，得真跑一次。"),
    ],
    "tools": [
        ("我先建文件", [{"id": "call_1", "name": "todo",
                        "arguments": json.dumps({"items": [{"text": "建 hello.txt", "status": "doing"},
                                                            {"text": "改第一行", "status": "pending"},
                                                            {"text": "读回来核对", "status": "pending"}]})}],
         "这活分三步：先建文件，再改第一行，最后读回来核对。少了第三步就等于没验——"
         "工具说成功不代表盘上真是那样。写之前还要留快照，这样用户想反悔时能退回上一版。"),
        ("写入", [{"id": "call_2", "name": "write",
                   "arguments": json.dumps({"path": "hello.txt", "content": "first line\nsecond line\n"})}]),
        ("改一行", [{"id": "call_3", "name": "edit",
                     "arguments": json.dumps({"path": "hello.txt", "old_string": "first line",
                                              "new_string": "hello from HaoAI PC"})}]),
        ("读回来核对", [{"id": "call_4", "name": "read", "arguments": json.dumps({"path": "hello.txt"})}]),
        ("做完了：hello.txt 的第一行已改成 `hello from HaoAI PC`，第二行保持 `second line`。"
         "我是真读过文件才这么说的（上面 read 的结果就是证据）。\n\n"
         "## 变更摘要\n- 新建 `hello.txt`\n- 改了 **第一行**\n- 读回核对过\n\n"
         "| 步骤 | 工具 | 结果 |\n|---|---|---|\n| 建文件 | write | 3 行 |\n| 改一行 | edit | 替换 1 处 |\n\n"
         "```kotlin\nval s = Snapshots.forPath(\"hello.txt\")\nprintln(s?.keyOf())\n```\n\n"
         "> 提醒：改动前已经留过快照，可以随时退回上一版。\n", None,
         "最后要把证据说清楚：读过文件才算做完。顺手给一段代码块和列表，前端才有东西可渲染。"),
    ],
    # code：模型跑一段 python，脚本往自己的运行目录里写一张 PNG。
    # 这条要验的是"run_code 产出的图真的回到上下文并显示出来"，
    # 所以只用标准库（base64/hex + 写文件），不赌 matplotlib 装没装。
    "code": [
        ("先算一遍并画张点", [{"id": "call_c1", "name": "run_code",
                               "arguments": json.dumps({"lang": "python", "code": CODE_PY})}]),
        ("跑完了：上面那张 PNG 是脚本自己写出来的，图我也看见了。", None),
    ],
    # 媒体工具：探长度 → 转码 → 抽音轨 → 抽一帧。
    # 配合 ui-shot.sh 的 PRE_CLIP（现造一段真素材），跑完界面上那两个播放器是
    # 真解码出画面的，不是摆样子的空壳；抽帧那条还会把 png 递回给模型，
    # 于是"工具产出的像素"这条路也顺带验了一次。
    "media": [
        ("先探一下素材有多长", [{"id": "call_m1", "name": "media",
                                "arguments": json.dumps({"sub": "info", "input": CLIP})}]),
        ("压一个 160 宽的小版本", [{"id": "call_m2", "name": "media",
                                    "arguments": json.dumps({"sub": "transcode", "input": CLIP,
                                                             "output": "小.mp4", "width": 160})}]),
        ("抽音轨", [{"id": "call_m3", "name": "media",
                     "arguments": json.dumps({"sub": "audio", "input": CLIP, "output": "音轨.mp3"})}]),
        ("抽第 2 秒那一帧", [{"id": "call_m4", "name": "media",
                              "arguments": json.dumps({"sub": "frame", "input": CLIP, "start": "2"})}]),
        ("三样都好了：小.mp4 是 160 宽的转码版、音轨.mp3 是抽出来的声音、"
         "第 2 秒那一帧我已经看见画面了。上面两个播放器都能直接放。", None),
    ],
    # 录屏这条链路要**真的**录一段桌面：开录 → 停 → 界面上出现一个真能解码的播放器。
    # stop 不带 id —— 只有一场在录时那是让人回去抄 id，多此一举。
    "rec": [
        ("先开一场录屏", [{"id": "call_rec1", "name": "record",
                           "arguments": json.dumps({"action": "start", "fps": 10, "maxSeconds": 30})}]),
        ("停掉它", [{"id": "call_rec2", "name": "record",
                     "arguments": json.dumps({"action": "stop"})}]),
        ("录完了：上面那段就是刚才的桌面，能直接放；要剪成片段或抽封面，交给 media 工具。", None),
    ],
    # 子任务：父会话派一个 task，子会话再派一个（被深度上限挡住）——
    # 界面上要看得见"子任务在跑什么、结论怎么回来的"，所以得有这份剧本。
    "sub": [
        ("我派个子任务去调研，省得中间过程灌进主上下文",
         [{"id": "call_sub", "name": "task",
           "arguments": json.dumps({"prompt": "读 hello.txt 并告诉我里面有什么", "label": "调研 hello.txt"})}],
         "这件事要读好几遍文件，交给子任务去做，我只要它的结论。"),
        ("子任务的结论已经够我回答了：文件里是 hello from HaoAI PC。", None),
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
    # 永远不收尾的一轮接一轮 —— 存在的唯一理由是让"停止"按钮可被验证：
    # 其它模式跑完就结束，快到根本来不及点。每轮 sleep 0.6s 让界面看得见。
    "loop": [
        ("我再数一轮", [{"id": "call_loop", "name": "shell",
                         "arguments": json.dumps({"command": "echo round", "shell": "bash", "timeout": 30})}]),
    ],
}

# 子任务单独停止：父任务派一条"一直数"的调研，界面上要能只停这一条。
# 父子共用"按 tool_rounds 取第几行"这一套会串台（两边的第 0 轮长得一样），
# 所以按请求里有没有子任务自己的标记分道 —— 和 multi 模式同一个办法。
SUBLOOP_MARK = "子任务现场"
SUBLOOP_PARENT = [
    ("我派个子任务去数数", [{"id": "t1", "name": "task",
        "arguments": json.dumps({"prompt": SUBLOOP_MARK + "：一轮一轮数，数到八再说", "label": "数数"})}],
     "这件事要反复看好几轮，交给子任务，我只要它的结论。"),
    ("子任务回来了，我照它的结论收尾。", None),
]
SUBLOOP_CHILD = [
    ("我先看一眼", [{"id": "s0", "name": "shell",
        "arguments": json.dumps({"command": "echo one", "shell": "bash", "timeout": 30})}]),
] + [("我再数一轮", [{"id": "s%d" % i, "name": "shell",
        "arguments": json.dumps({"command": "echo round-%d" % i, "shell": "bash", "timeout": 30})}])
     for i in range(1, 9)] + [
    ("数完了：一共数了 9 轮，每轮 echo 一个数。", None),
]

# multi：一个网关同时喂好几条会话，各自一份剧本。
# 三条的话术与文件都不同，所以"事件串台"在界面上是看得见的（丙的回答出现在甲那条=立刻能发现）。
ROUTED = {
    "并行甲": PLAN["loop"],
    "并行乙": [
        ("乙这边要动文件了", [{"id": "call_b1", "name": "write",
                               "arguments": json.dumps({"path": "mock-b.txt", "content": "来自乙\n"})}]),
        ("乙做完了：mock-b.txt 已写入，这条的输出只属于乙。", None),
    ],
    "并行丙": [        # 这句话故意写得比一个 SSE 分片（14 字符）长：前端要是拿"当前这一片"重画整个气泡，
        # 这里就会只剩尾巴，截图上看得出来，断言也能量出来。
        ("丙先读一个文件；这句话应该整段出现在同一个气泡里，不会被后面的分片顶掉。",
         [{"id": "call_c1", "name": "read", "arguments": json.dumps({"path": "notes.md"})}]),
        (PLAN["chat"][0][0], None),
    ],
    # 与乙同类（要审批），用来验"两条会话同时等确认"时弹窗会不会互相盖掉
    "并行丁": [
        ("丁也要写个文件", [{"id": "call_d1", "name": "write",
                             "arguments": json.dumps({"path": "mock-d.txt", "content": "D\n"})}]),
        ("丁做完了：mock-d.txt。", None),
    ],
    # ask_user 的选项按钮挂在 data-i 上，重写弹窗时这段绑定丢过一次 —— 只有真点一下才发现
    "并行戊": [
        ("戊先问一句", [{"id": "call_e1", "name": "ask_user",
                         "arguments": json.dumps({"question": "要绿色还是蓝色？", "options": ["绿色", "蓝色"]})}]),
        ("戊记下了你选的颜色，写进 mock-e.txt。",
         [{"id": "call_e2", "name": "write",
           "arguments": json.dumps({"path": "mock-e.txt", "content": "ok\n"})}]),
        ("戊做完了。", None),
    ],
}


# 预览面板这条链路要一个"点得动、也看得到结果"的靶页：
# 光把画面搬过来不算通 —— 判据是"人在这边点一下，那边的计数器真的 +1，
# 而且这件事能从 CDP 读回来"。所以按钮居中（画面是等比缩的，点图的正中就是点屏幕正中），
# 并把计数与收到的字写进 title —— 面板的状态行读的就是 document.title。
HELLO = (
    "<!doctype html><meta charset=utf-8><title>\u9884\u89c8\u9776\u9875 0</title>"
    "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;"
    "align-items:center;justify-content:center;gap:18px;font:16px system-ui;background:#fafafa}"
    "#b{font-size:22px;padding:16px 26px}#i{font-size:18px;width:240px;padding:8px}</style>"
    "<div id=c>\u8ba1\u6570 0</div>"
    "<button id=b>\u70b9\u6211 +1</button>"
    "<input id=i autofocus placeholder=\"\u8fd9\u91cc\u6536\u5b57\">"
    "<script>var n=0;function sync(){document.title='\u9884\u89c8\u9776\u9875 '+n"
    "+'\uff5c'+document.getElementById('i').value}"
    "document.getElementById('b').onclick=function(){n++;"
    "document.getElementById('c').textContent='\u8ba1\u6570 '+n;sync()};"
    "document.getElementById('i').oninput=sync;</script>"
)

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    @staticmethod
    def plan_for(msgs):
        """multi 模式：按请求里出现的标记挑剧本。

        多会话并行时不能按"第几个请求"发剧本 —— 谁先问到模型不确定，
        串了台之后前端看到的是 A 的回答带着 B 的工具卡，根本分不清是产品坏了还是测试坏了。
        标记来自用户第一句话里的词，请求体会把历史原样带回来，所以每条会话稳定命中自己那份。
        """
        joined = " ".join(str(m.get("content") or "") for m in msgs)
        for key in ROUTED:
            if key in joined:
                return key, ROUTED[key]
        return None, PLAN["chat"]

    def do_GET(self):
        if self.path.startswith("/hello"):
            body = HELLO.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith("/v1/models"):
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
        key, plan = (None, PLAN.get(MODE, PLAN["tools"]))
        if MODE == "multi":
            key, plan = self.plan_for(msgs)
        if MODE == "subloop":
            joined = " ".join(str(m.get("content") or "") for m in msgs)
            plan = SUBLOOP_CHILD if SUBLOOP_MARK in joined else SUBLOOP_PARENT
            if tool_rounds:
                time.sleep(0.5)   # 每轮慢一点，界面上才来得及点"停掉它" 
        idx = min(tool_rounds, len(plan) - 1)
        row = plan[idx]
        text = row[0]
        calls = row[1] if len(row) > 1 else None
        # 剧本里的第三项就是这一轮的"思考"，没有就不发（真实网关也是有的模型有、有的没有）
        reasoning = row[2] if len(row) > 2 else None
        if key == "并行甲" or (MODE == "loop" and key is None):
            # 每轮慢一点，界面上才看得见"正在跑"，也才来得及按停止。
            time.sleep(0.6)
            calls = [dict(calls[0], id="call_loop_%d" % tool_rounds,
                          arguments=json.dumps({"command": "echo round-%d" % tool_rounds,
                                                "shell": "bash", "timeout": 30}))]
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
        for ch in delta_chunks(text, calls, reasoning):
            self.wfile.write(sse(ch))
            self.wfile.flush()
            # 思考分片发得慢一点：界面上"思考中"那张卡要存在一秒多，
            # 才看得见它长什么样（真实的推理模型本来就是这个节奏，一秒几个 token）。
            d = (ch.get("choices") or [{}])[0].get("delta") or {}
            time.sleep(0.16 if d.get("reasoning_content") else 0.01)
        self.wfile.write(sse({"usage": {"prompt_tokens": 1234 + 200 * idx, "completion_tokens": 87}}))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--mode", default="tools", choices=list(PLAN.keys()) + ["multi", "subloop"])
    a = ap.parse_args()
    global MODE
    MODE = a.mode
    print(f"mock openai on http://127.0.0.1:{a.port}/v1  mode={MODE}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
