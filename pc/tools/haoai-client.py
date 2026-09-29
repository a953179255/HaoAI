#!/usr/bin/env python3
"""HaoAI PC 的最小客户端 —— 只用标准库，没有 pip 依赖。

    python pc/tools/haoai-client.py task "把 README 里和代码对不上的部分改过来"
    python pc/tools/haoai-client.py task --auto "只读一遍 notes.md，告诉我它讲了什么"
    python pc/tools/haoai-client.py state
    python pc/tools/haoai-client.py sessions --q 降级
    python pc/tools/haoai-client.py runs
    python pc/tools/haoai-client.py usage
    python pc/tools/haoai-client.py stop --sid pc1234

为什么要有一个客户端而不只给 curl：`POST /api/task` 只返回 sid，**结果在 SSE 里**，
而审批卡也在同一条流上 —— 一个脚本要能跑完一条任务，就必须既读流又能回答。
这套东西 curl 写起来很别扭，所以这里给一份能直接抄的。

`--auto` 是把这条会话切到自动档（低/中危不再打断），但**高危照样会弹卡** ——
那是保护，不是 bug：定时任务与任务链撞上一次 `git push --force` 之前应该停下来等人。
"""
import argparse
import json
import os
import queue
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE = os.environ.get("HAOAI_BASE", "http://127.0.0.1:8712")

# 输出必须是 UTF-8：Windows 上把 stdout 重定向到文件或管道时，Python 会按系统代码页
# （这台机器是 cp936）编码，中文全变成乱码 —— 症状是"客户端看着像坏了"，
# 而实际只是编码。终端里看着正常，一重定向就坏，这种坑最容易漏。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def http(base, path, payload=None, timeout=30):
    """一个 JSON 请求。POST 只在给了 payload 时发生。"""
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base + path, data=data, headers={"Content-Type": "application/json"},
        method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
    except Exception as e:
        raise SystemExit("连不上 %s%s：%s\n（服务起来了吗？`haoai serve`，或加 --base 指个端口）"
                         % (base, path, e))
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw}


def reader(base, out, stop):
    """把 SSE 帧拆成 (event, data) 丢进队列。另开线程读，主线程才既能打印又能等人回答。"""
    try:
        with urllib.request.urlopen(base + "/api/events", timeout=None) as r:
            ev, buf = "", []
            while not stop.is_set():
                line = r.readline()
                if not line:
                    break
                s = line.decode("utf-8", "replace").rstrip("\r\n")
                if s.startswith("event: "):
                    ev = s[7:].strip()
                elif s.startswith("data: "):
                    buf.append(s[6:])
                elif s == "":
                    if ev:
                        raw = "".join(buf)
                        try:
                            out.put((ev, json.loads(raw)))
                        except Exception:
                            out.put((ev, raw))
                    ev, buf = "", []
    except Exception as e:
        if not stop.is_set():
            out.put(("__stream_error__", str(e)))
    finally:
        out.put(("__end__", None))


def ask_hunks(d):
    """逐块审批：把每一块摆出来，让人说不要哪几块。返回 None = 不看勾选，整条决定。"""
    hunks = d.get("hunks") or []
    if len(hunks) < 2:
        return None
    print("\n  这次改动分成 %d 处：" % len(hunks))
    for h in hunks:
        print("  [%d] 第 %s 处 · 旧第 %s 行起 · %s" % (h["no"], h["no"], h.get("at", "?"), h.get("stat", "")))
        for ln in (h.get("text") or "").split("\n")[1:8]:
            print("        " + ln)
    raw = input("  不要哪几块？（逗号分隔，如 2,4；回车=全要）> ").strip()
    if not raw:
        return None
    off = {int(x) for x in raw.replace(" ", "").split(",") if x.isdigit()}
    if not off:
        return None
    bits = "".join("0" if h["no"] in off else "1" for h in hunks)
    return "partial:" + bits


def decide(base, d):
    """审批卡与提问都在同一条流上，所以回答也得从读流的那个线程里发出去。"""
    if d.get("question") is not None:
        opts = d.get("options") or []
        print("\n  Agent 问：%s" % d["question"])
        if opts:
            print("  选项：" + " / ".join("%d.%s" % (i + 1, o) for i, o in enumerate(opts)))
        ans = input("  回答> ").strip() or (opts[0] if opts else "按你的建议来")
        if ans.isdigit() and opts and 1 <= int(ans) <= len(opts):
            ans = opts[int(ans) - 1]
        http(base, "/api/decide", {"id": d["id"], "decision": "", "answer": ans})
        return
    print("\n  需要批准：%s（%s%s）" % (d.get("title", ""), d.get("kind", ""),
                                   " · " + d["riskLabel"] if d.get("riskLabel") else ""))
    print("  " + (d.get("detail") or "").replace("\n", "\n  "))
    part = ask_hunks(d)
    if part:
        http(base, "/api/decide", {"id": d["id"], "decision": part, "answer": ""})
        print("  → 按勾选部分放行")
        return
    c = input("  允许一次[y] / 本任务都允许[a] / 以后这类都允许[r] / 拒绝[n] > ").strip().lower()
    decision = {"y": "allow_once", "a": "allow_session", "r": "allow_rule"}.get(c, "deny")
    http(base, "/api/decide", {"id": d["id"], "decision": decision, "answer": ""})
    print("  → " + {"allow_once": "允许一次", "allow_session": "本任务都允许",
                  "allow_rule": "写入规则并允许"}.get(decision, "拒绝"))


def cmd_task(base, args):
    body = {"text": args.text}
    if args.sid:
        body["sid"] = args.sid
    r = http(base, "/api/task", body)
    if not r.get("ok"):
        raise SystemExit("任务没起来：%s" % (r.get("error") or r.get("_raw") or json.dumps(r, ensure_ascii=False)))
    sid = r["sid"]
    if args.auto:
        http(base, "/api/mode", {"sid": sid, "mode": "auto"})
    print("会话 %s（%s）。Ctrl+C 只断开这个客户端，**不会停掉电脑上的任务** —— 要停用 stop。"
          % (sid, "自动档" if args.auto else "按当前权限档"))

    out, stop = queue.Queue(), threading.Event()
    threading.Thread(target=reader, args=(base, out, stop), daemon=True).start()
    answered_at = time.time()
    streaming = False
    try:
        while True:
            try:
                ev, data = out.get(timeout=0.4)
            except queue.Empty:
                ev, data = None, None
            if ev is not None:
                if ev == "delta":
                    if not streaming:
                        print("HaoAI: ", end="", flush=True)
                        streaming = True
                    print(data if isinstance(data, str) else "", end="", flush=True)
                elif ev == "answer":
                    # 正文是一边收 delta 一边打印的，answer 带来的是同一份的完整版：
                    # 已经在流了就只补一个换行，没流过（比如空回答）才整句打出来。
                    if streaming:
                        print()
                        streaming = False
                    else:
                        print("HaoAI: " + (data or "").strip())
                elif ev == "tool":
                    if isinstance(data, dict):
                        if data.get("state") == "run":
                            print("\n· %s %s" % (data.get("name", ""), data.get("brief", "")))
                        else:
                            print("  %s %s → %s" % ("✓" if data.get("ok") else "✗",
                                                  data.get("name", ""),
                                                  (data.get("out") or "").replace("\n", " ")[:90]))
                elif ev == "notice":
                    print("\n! %s" % data)
                elif ev == "err":
                    print("\n× %s" % data)
                elif ev in ("approval", "ask"):
                    if streaming:
                        print()
                        streaming = False
                    decide(base, data if isinstance(data, dict) else {})
                    answered_at = time.time()
                elif ev == "__stream_error__":
                    print("\n（事件流断了：%s —— 任务还在电脑上跑，重开本命令或看 state）" % data)
                    return 1
                elif ev == "__end__":
                    print("\n（服务端关掉了事件流）")
                    return 1
            # 收口判据用状态，不用"看到某个事件"：一轮答完之后可能还有工具要跑，
            # 而 answer/usage 的时机随实现会变 —— running=false 才是"这条自己收的尾"。
            if time.time() - answered_at > 2.0:
                answered_at = time.time()
                st = http(base, "/api/state?sid=" + sid, timeout=10)
                if not st.get("running") and out.empty():
                    break
    except KeyboardInterrupt:
        print("\n（已断开。任务还在跑：haoai-client stop --sid %s）" % sid)
        return 130
    finally:
        stop.set()
    if streaming:
        print()
    return 0


def cmd_state(base, args):
    st = http(base, "/api/state" + ("?sid=" + args.sid if args.sid else ""))
    msgs = st.get("messages") or []
    print("会话 %s｜档 %s｜模型 %s%s｜跑着 %s｜%d 条消息"
          % (st.get("sid") or args.sid or "(当前)", st.get("mode"), st.get("model"),
             "（实际在答：%s）" % st["modelNow"] if st.get("modelNow") and
             st["modelNow"] != st.get("model") else "",
             "是" if st.get("running") else "否", len(msgs)))
    for m in msgs[-args.tail:]:
        who = {"user": "你", "assistant": "HaoAI", "tool": "工具"}.get(m.get("role"), m.get("role"))
        body = (m.get("content") or "").replace("\n", " ")
        print("  %-6s %s%s" % (who, body[:150], "…" if len(body) > 150 else ""))
        if m.get("notice"):
            print("         ! %s" % m["notice"].replace("\n", " | ")[:150])
    return 0


def cmd_sessions(base, args):
    for s in http(base, "/api/sessions" + ("?q=" + urllib.parse.quote(args.q) if args.q else "")):
        print("%-12s %-28s %s" % (s.get("id", ""), (s.get("title") or "")[:28],
                                  time.strftime("%m-%d %H:%M", time.localtime((s.get("updated") or 0) / 1000))))
    return 0


def cmd_runs(base, args):
    for r in (http(base, "/api/runs").get("items") or []):
        print("%-10s %-24s %s 轮 %6.1fs %s %s"
              % (time.strftime("%m-%d %H:%M", time.localtime((r.get("started") or 0) / 1000)),
                 (r.get("title") or "")[:24], r.get("turns"), (r.get("ms") or 0) / 1000,
                 "停" if r.get("stopped") else "成", (r.get("out") or "").replace("\n", " ")[:40]))
    return 0


def cmd_usage(base, args):
    u = http(base, "/api/usage")
    print(json.dumps(u, ensure_ascii=False, indent=2)[:2000])
    return 0


def cmd_stop(base, args):
    print(http(base, "/api/stop", {"sid": args.sid or ""}))
    return 0


def main():
    p = argparse.ArgumentParser(description="HaoAI PC 的最小客户端（只用标准库）")
    p.add_argument("--base", default=DEFAULT_BASE, help="服务地址，默认 %s" % DEFAULT_BASE)
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("task", help="发一个任务并跟着看")
    t.add_argument("text")
    t.add_argument("--sid", help="接着哪条会话说（默认新起一条）")
    t.add_argument("--auto", action="store_true", help="把这条会话切到自动档（高危仍会问）")
    s = sub.add_parser("state", help="看一条会话现在什么样")
    s.add_argument("--sid")
    s.add_argument("--tail", type=int, default=6, help="打印最后几条消息")
    ss = sub.add_parser("sessions", help="列会话")
    ss.add_argument("--q", help="连消息正文一起搜")
    sub.add_parser("runs", help="最近跑了哪些任务")
    sub.add_parser("usage", help="用量")
    sp = sub.add_parser("stop", help="停掉一条会话正在跑的任务")
    sp.add_argument("--sid")
    args = p.parse_args()
    return {"task": cmd_task, "state": cmd_state, "sessions": cmd_sessions,
            "runs": cmd_runs, "usage": cmd_usage, "stop": cmd_stop}[args.cmd](args.base, args)


if __name__ == "__main__":
    sys.exit(main())
