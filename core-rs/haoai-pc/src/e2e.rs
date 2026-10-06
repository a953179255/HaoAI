//! 端到端回归：`/api/task` → 拼请求 → 流式网关 → SSE 事件 → 会话落盘。
//!
//! 网关是**测试内自建的假服务器**（把模型换成脚本），所以这条测试不需要密钥、
//! 不需要网络、也不需要他本机那个 llama-server 在跑。
//! 这正是 `Provider.kt` 注释里说的"留 ChatClient 这条缝"的用法。
#![cfg(test)]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::{engine, App};
use serde_json::Value;

/// 起一个只会说固定台词的 OpenAI 兼容网关。返回（端口，收到的请求体）。
fn fake_gateway(chunks: Vec<String>) -> (u16, Arc<Mutex<String>>) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let seen = Arc::new(Mutex::new(String::new()));
    let seen2 = seen.clone();
    thread::spawn(move || {
        for stream in l.incoming() {
            let Ok(mut s) = stream else { continue };
            // 读用副本，写完响应还要用原 s：BufReader 借走 s 就还不回来了
            let mut br = BufReader::new(s.try_clone().unwrap());
            let mut clen = 0usize;
            loop {
                let mut line = String::new();
                if br.read_line(&mut line).unwrap_or(0) == 0 {
                    return;
                }
                if line == "\r\n" || line == "\n" {
                    break;
                }
                // 头名大小写不敏感：ureq 发的是小写 content-length，按前缀精确匹配会漏，
                // 漏了就把 body 读成 0 字节，表现为"请求里没有系统提示"这种误导性失败
                if line.to_ascii_lowercase().starts_with("content-length:") {
                    clen = line.split_once(':').map(|(_, v)| v.trim().parse().unwrap_or(0)).unwrap_or(0);
                }
            }
            let mut body = vec![0u8; clen];
            let _ = br.read_exact(&mut body);
            *seen2.lock().unwrap() = String::from_utf8_lossy(&body).to_string();

            let mut out = String::from(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n",
            );
            for c in &chunks {
                let frame = format!(
                    "data: {{\"choices\":[{{\"delta\":{{\"content\":\"{c}\"}}}}]}}\n\n"
                );
                out.push_str(&format!("{:x}\r\n{}\r\n", frame.len(), frame));
            }
            let last = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\ndata: [DONE]\n\n";
            out.push_str(&format!("{:x}\r\n{}\r\n", last.len(), last));
            out.push_str("0\r\n\r\n");
            let _ = s.write_all(out.as_bytes());
            let _ = s.flush();
        }
    });
    (port, seen)
}

fn temp_root(tag: &str, port: u16) -> PathBuf {
    let p = std::env::temp_dir().join(format!("haoai-e2e-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(p.join("sessions")).unwrap();
    std::fs::write(
        p.join("settings.json"),
        format!(
            r#"{{"providerName":"假网关","baseUrl":"http://127.0.0.1:{port}/v1","model":"fake-model","workspace":"G:/hbt/pc-task-demo","permissionMode":"auto","maxTokens":4096,"temperature":0.3,"contextChars":128000,"compactTriggerChars":60000,"toolsOff":[]}}"#
        ),
    )
    .unwrap();
    std::fs::write(p.join("apikey"), "sk-test-ascii-only").unwrap();
    std::fs::write(
        p.join("sessions/pc-e2e.json"),
        r#"{"id":"e2e","title":"e2e 会话","workspace":"G:/hbt/pc-task-demo","mode":"auto","persona":"","role":"","model":"fake-model","updated":1,"promptTokens":0,"completionTokens":0,"todos":[],"messages":[{"role":"user","content":"先前的一句"}]}"#,
    )
    .unwrap();
    p
}

#[test]
fn provider_reads_usage_from_last_frame() {
    let (port, _) = fake_gateway(vec!["喂".into()]);
    let root = temp_root("usage", port);
    let store = crate::store::Store::new(root.clone());
    let s = store.settings();
    let mut on_text = |_: &str| {};
    let mut on_reason = |_: &str| {};
    let t = crate::provider::chat(
        &s,
        "sk-test",
        r#"{"model":"fake-model","messages":[]}"#,
        &mut on_text,
        &mut on_reason,
    )
    .expect("假网关应成功返回");
    assert_eq!(t.text, "喂");
    assert_eq!(t.finish, "stop", "finish_reason 没解析出来");
    assert_eq!(t.prompt_tokens, 11, "usage.prompt_tokens 没解析出来");
    assert_eq!(t.completion_tokens, 7, "usage.completion_tokens 没解析出来");
    let _ = std::fs::remove_dir_all(&root);
}

/// 脚本化网关：第 n 个请求回第 n 段预设响应。用来演"先要工具、再给结论"。
/// 返回（端口，收到的每个请求体）。
fn scripted_gateway(responses: Vec<String>) -> (u16, Arc<Mutex<Vec<String>>>) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    (port, spawn_scripted(l, responses, |_| {}))
}

/// 同上，但把监听 socket 交出来：调用方可以先建好 App、再让网关带着回调开跑。
/// `on_req(idx)` 是"第 idx 个模型请求正在被应答"这个时机 —— 按停止那条测试要在
/// **本轮已经开始、工具还没跑完**的当口置旗子，用 sleep 猜时机会得到一条时好时坏的测试。
fn spawn_scripted(
    l: TcpListener,
    responses: Vec<String>,
    on_req: impl Fn(usize) + Send + 'static,
) -> Arc<Mutex<Vec<String>>> {
    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let seen2 = seen.clone();
    thread::spawn(move || {
        for stream in l.incoming() {
            let Ok(mut s) = stream else { continue };
            let mut br = BufReader::new(s.try_clone().unwrap());
            let mut clen = 0usize;
            loop {
                let mut line = String::new();
                if br.read_line(&mut line).unwrap_or(0) == 0 {
                    return;
                }
                if line == "\r\n" || line == "\n" {
                    break;
                }
                // 头名大小写不敏感：ureq 发的是小写 content-length
                if line.to_ascii_lowercase().starts_with("content-length:") {
                    clen = line.split_once(':').map(|(_, v)| v.trim().parse().unwrap_or(0)).unwrap_or(0);
                }
            }
            let mut body = vec![0u8; clen];
            let _ = br.read_exact(&mut body);
            let idx = {
                let mut g = seen2.lock().unwrap();
                g.push(String::from_utf8_lossy(&body).to_string());
                g.len() - 1
            };
            on_req(idx);
            let payload = responses.get(idx).cloned().unwrap_or_default();
            let mut out = String::from(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n",
            );
            out.push_str(&format!("{:x}\r\n{}\r\n", payload.len(), payload));
            out.push_str("0\r\n\r\n");
            let _ = s.write_all(out.as_bytes());
            let _ = s.flush();
        }
    });
    seen
}

/// 网关回一段"要调这些工具"的 SSE。每一项是 (call id, 工具名, 参数 JSON)。
fn tool_calls_frame(calls: &[(&str, &str, Value)]) -> String {
    use serde_json::json;
    let list: Vec<Value> = calls
        .iter()
        .enumerate()
        .map(|(i, (id, name, args))| {
            json!({"index": i, "id": id, "type": "function", "function": {
                "name": name, "arguments": args.to_string()
            }})
        })
        .collect();
    let frame = json!({"choices": [{"delta": {"content": "先动手", "tool_calls": list},
        "finish_reason": "tool_calls"}]});
    format!("data: {frame}\n\ndata: [DONE]\n\n")
}

/// 网关回一段"这就是最终回答"的 SSE。
fn final_frame(text: &str) -> String {
    format!(
        "data: {{\"choices\":[{{\"delta\":{{\"content\":\"{text}\"}},\"finish_reason\":\"stop\"}}]}}\n\ndata: [DONE]\n\n"
    )
}

/// 临时状态根 + App，会话档位可指定（plan 的只读闸要看的就是这个 mode）。
/// 工作区里备一个真能读的文件，工具执行要有对象才看得出"到底跑没跑"。
fn app_with_session(tag: &str, port: u16, sid: &str, mode: &str) -> (PathBuf, Arc<App>) {
    let root = temp_root(tag, port);
    std::fs::create_dir_all(root.join("ws/src")).unwrap();
    std::fs::write(root.join("ws/src/a.rs"), "line1\nline2\nline3\n").unwrap();
    std::fs::write(
        root.join(format!("sessions/pc-{sid}.json")),
        format!(
            r#"{{"id":"{sid}","title":"{tag} 会话","workspace":"{}","mode":"{mode}","persona":"","role":"","model":"fake-model","updated":1,"promptTokens":0,"completionTokens":0,"todos":[],"messages":[]}}"#,
            root.join("ws").display().to_string().replace('\\', "/")
        ),
    )
    .unwrap();
    let app = App::at(root.clone());
    app.open_for_test(sid);
    (root, app)
}

#[test]
fn task_roundtrip_streams_and_persists() {
    let (port, seen) = fake_gateway(vec!["你好".into(), "，我在".into()]);
    let root = temp_root("rt", port);

    let app = App::at(root.clone());
    app.open_for_test("e2e");

    // 挂一个订阅者，把服务端推的事件全收下来
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    app.bus.lock().unwrap().push(tx);

    engine::begin(Arc::clone(&app), "e2e".into(), "今天天气怎么样".into()).unwrap();

    let deadline = Instant::now() + Duration::from_secs(20);
    let mut events = Vec::new();
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(f) => events.push(f),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if !app.is_running("e2e") && !events.is_empty() {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    let names: Vec<String> = events
        .iter()
        .filter_map(|f| f.lines().find_map(|l| l.strip_prefix("event: ")).map(String::from))
        .collect();

    assert!(names.contains(&"user".to_string()), "缺 user 事件：{names:?}");
    assert!(names.contains(&"delta".to_string()), "缺 delta 事件：{names:?}");
    assert!(names.contains(&"answer".to_string()), "缺 answer 事件：{names:?}");
    assert_eq!(
        names.first().map(|s| s.as_str()),
        Some("user"),
        "事件顺序应以 user 开头"
    );
    assert_eq!(
        *names.last().unwrap(),
        "sessions",
        "事件序列应以 sessions 收尾（让侧栏刷新）"
    );

    // 请求体：应带上系统提示、历史里的旧句子、以及刚问的这句
    let req = seen.lock().unwrap().clone();
    assert!(req.contains("你是 HaoAI"), "请求里没有系统提示");
    assert!(req.contains("先前的一句"), "请求里没有历史");
    assert!(req.contains("今天天气怎么样"), "请求里没有本轮这句");
    assert!(req.contains("\"tools\""), "请求里应带上工具 schema");
    assert!(req.contains("\"stream\":true"), "应走流式");

    // 落盘：新的一句 assistant 进了会话文件，累计 token 也记上了
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-e2e.json")).unwrap())
            .unwrap();
    let msgs = saved["messages"].as_array().unwrap();
    assert_eq!(msgs.len(), 3, "原有 1 条 + 本轮 user + assistant = 3 条");
    assert_eq!(msgs[0]["content"], "先前的一句");
    assert_eq!(msgs[1]["role"], "user");
    assert_eq!(msgs[1]["content"], "今天天气怎么样");
    assert_eq!(msgs[2]["role"], "assistant");
    assert_eq!(msgs[2]["content"], "你好，我在");
    assert_eq!(saved["promptTokens"], 11, "落盘内容：{saved}");
    assert_eq!(saved["completionTokens"], 7);
    // 键序必须保持原样（preserve_order）：洗成字母序会让两端文件形状分叉
    let keys: Vec<&String> = saved.as_object().unwrap().keys().collect();
    assert_eq!(keys[0], "id", "会话文件键序被打乱了：{keys:?}");

    // 回合结束后 running 必须归零，且 /api/state 报得出真实历史
    assert!(!app.is_running("e2e"));
    let std_root = crate::store::Store::new(root.clone());
    let sf = std_root.restore("e2e").unwrap();
    let v = crate::state::View {
        settings: &app.settings,
        session: Some(&sf),
        session_id: "e2e",
        store: &std_root,
        running: false,
        usage: app.usage_of("e2e"),
        pending: &[],
    };
    let st: Value = serde_json::from_str(&crate::state::state_json(&v)).unwrap();
    assert_eq!(st["messages"].as_array().unwrap().len(), 3);
    assert_eq!(st["usage"]["prompt"], 11);

    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn tool_loop_runs_read_then_answers() {
    use serde_json::json;

    // 用 json! 构造而不是手写转义：arguments 是"JSON 串里再套一层 JSON 串"，
    // 手写 \\\" 极易错，错了症状又离病因很远
    let frame1 = json!({"choices":[{"delta":{
        "content":"先看一眼",
        "tool_calls":[{"index":0,"id":"call_1","function":{
            "name":"read","arguments": json!({"path":"src/a.rs"}).to_string()
        }}]
    },"finish_reason":"tool_calls"}]});
    let frame2 = json!({"choices":[{"delta":{"content":"读完了，共 3 行"},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":20,"completion_tokens":4}});
    let rsp1 = format!("data: {frame1}\n\ndata: [DONE]\n\n");
    let rsp2 = format!("data: {frame2}\n\ndata: [DONE]\n\n");

    let (port, seen) = scripted_gateway(vec![rsp1, rsp2]);
    let root = temp_root("tools", port);
    // 工具要有一个真的工作区可读
    std::fs::create_dir_all(root.join("ws/src")).unwrap();
    std::fs::write(root.join("ws/src/a.rs"), "line1\nline2\nline3\n").unwrap();
    std::fs::write(
        root.join("sessions/pc-tt.json"),
        format!(
            r#"{{"id":"tt","title":"工具会话","workspace":"{}","mode":"auto","persona":"","role":"","model":"fake-model","updated":1,"promptTokens":0,"completionTokens":0,"todos":[],"messages":[]}}"#,
            root.join("ws").display().to_string().replace('\\', "/")
        ),
    )
    .unwrap();

    let app = App::at(root.clone());
    app.open_for_test("tt");
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    app.bus.lock().unwrap().push(tx);

    engine::begin(Arc::clone(&app), "tt".into(), "看看 a.rs 里有啥".into()).unwrap();
    assert!(engine::wait_idle(&app, "tt", 20), "回合没在 20 秒内结束");
    let mut events = Vec::new();
    while let Ok(f) = rx.try_recv() {
        events.push(f);
    }
    let tool_frames: Vec<&String> = events.iter().filter(|f| f.contains("event: tool")).collect();
    assert_eq!(tool_frames.len(), 2, "应有一对 tool 开始/结束事件：{tool_frames:?}");
    assert!(tool_frames[0].contains(r#""state":"run""#), "缺工具开始事件：{}", tool_frames[0]);
    assert!(tool_frames[1].contains(r#""ok":true"#), "read 应执行成功：{}", tool_frames[1]);
    assert!(tool_frames[1].contains("line1"), "工具结束事件应带输出：{}", tool_frames[1]);

    // 网关被调了两次，第二次的请求里必须带 tool 回复
    let reqs = seen.lock().unwrap().clone();
    assert_eq!(reqs.len(), 2, "应发生两轮模型调用，实际 {}", reqs.len());
    let second: Value = serde_json::from_str(&reqs[1]).unwrap();
    let msgs = second["messages"].as_array().unwrap();
    let tool_msg = msgs.iter().find(|m| m["role"] == "tool").expect("第二轮请求里没有 tool 回复");
    assert_eq!(tool_msg["tool_call_id"], "call_1", "tool_call_id 没回指");
    let body_txt = tool_msg["content"].as_str().unwrap();
    assert!(body_txt.contains("1: line1"), "read 的结果没进 tool 内容：{body_txt}");
    assert!(body_txt.contains("3: line3"));

    // 落盘：assistant 带 tool_calls、tool 带 tool_call_id，两条都在
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-tt.json")).unwrap())
            .unwrap();
    let sm = saved["messages"].as_array().unwrap();
    let a_calls = sm.iter().find(|m| m["tool_calls"].is_array()).expect("没有带 tool_calls 的 assistant");
    assert_eq!(a_calls["tool_calls"][0]["function"]["name"], "read");
    assert!(sm.iter().any(|m| m["role"] == "tool" && m["tool_call_id"] == "call_1"));
    assert_eq!(sm.last().unwrap()["role"], "assistant");
    assert_eq!(sm.last().unwrap()["content"], "读完了，共 3 行");

    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn same_session_cannot_run_twice() {
    let (port, _) = fake_gateway(vec!["x".into()]);
    let root = temp_root("busy", port);
    let app = App::at(root.clone());
    app.open_for_test("e2e");
    assert!(app.try_begin("e2e"), "第一次占坑应成功");
    assert!(!app.try_begin("e2e"), "同一条会话不允许并行两个任务");
    app.end_run("e2e");
    assert!(app.try_begin("e2e"), "结束后应能再占");
    let _ = std::fs::remove_dir_all(&root);
}

/// 计划模式：写类工具既**不该被模型看见**（schemas 按档位收窄），
/// 调了也**不该执行**（执行侧再挡一次）。两道都要，缺一道就是"只读"骗人。
#[test]
fn plan_mode_hides_and_refuses_write_tools() {
    use serde_json::json;
    let (port, seen) = scripted_gateway(vec![
        tool_calls_frame(&[
            ("call_1", "write", json!({"path": "src/new.rs", "content": "x"})),
            ("call_2", "todo", json!({"items": [{"text": "先列计划", "status": "pending"}]})),
        ]),
        final_frame("计划写好了"),
    ]);
    let (root, app) = app_with_session("plan", port, "pl", "plan");
    engine::turn(&app, "pl", "给我建个文件").expect("回合应跑完");

    let reqs = seen.lock().unwrap().clone();
    assert_eq!(reqs.len(), 2, "plan 档也该走完两轮");
    assert!(!reqs[0].contains(r#""name":"write""#), "plan 档的请求里不该有 write schema");
    assert!(!reqs[0].contains(r#""name":"shell""#), "plan 档的请求里不该有 shell schema");
    assert!(reqs[0].contains(r#""name":"read""#), "read 是只读的，应该在");
    assert!(reqs[0].contains(r#""name":"todo""#), "todo 是列计划本身要用的，应该在");

    let second: Value = serde_json::from_str(&reqs[1]).unwrap();
    let msgs = second["messages"].as_array().unwrap();
    let refused = msgs.iter().find(|m| m["tool_call_id"] == "call_1").expect("call_1 没有 tool 回复");
    assert_eq!(
        refused["content"],
        "计划模式是只读的，write 不能用。把要做的事写进计划，或用 ask_user 确认切档。"
    );
    let allowed = msgs.iter().find(|m| m["tool_call_id"] == "call_2").expect("call_2 没有 tool 回复");
    assert!(allowed["content"].as_str().unwrap().contains("先列计划"), "todo 在 plan 档要能跑：{allowed}");

    assert!(!root.join("ws/src/new.rs").exists(), "plan 档不许落任何文件");
    let _ = std::fs::remove_dir_all(&root);
}

/// 停止落在工具堆中间时，**每个 tool_call_id 仍要恰好一条回复**，
/// 且置旗之后不该再打第二次模型 —— "停止"不能变成"把这轮跑完再说"。
#[test]
fn stop_mid_tool_round_replies_every_call_id() {
    use serde_json::json;
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let (root, app) = app_with_session("stop", port, "sp", "auto");
    let seeder = Arc::clone(&app);
    let seen = spawn_scripted(
        l,
        vec![tool_calls_frame(&[
            ("call_1", "read", json!({"path": "src/a.rs"})),
            ("call_2", "read", json!({"path": "src/nope.rs"})),
        ])],
        move |i| if i == 0 { seeder.request_stop("sp"); },
    );

    let (tx, rx) = std::sync::mpsc::channel::<String>();
    app.bus.lock().unwrap().push(tx);
    engine::turn(&app, "sp", "跑到一半停").expect("回合应正常收尾");

    let mut frames = Vec::new();
    while let Ok(f) = rx.try_recv() {
        frames.push(f);
    }
    let interrupted = frames.iter().filter(|f| f.contains("已中断，未执行")).count();
    assert_eq!(interrupted, 2, "两个调用都该报未执行：{frames:?}");
    assert!(!frames.iter().any(|f| f.contains(r#""state":"run""#)), "被跳过的调用不该发开始事件");
    assert!(frames.iter().any(|f| f.contains("已按你的要求中断")), "缺收尾那句小字");
    assert_eq!(seen.lock().unwrap().len(), 1, "置旗之后不该再发模型请求");

    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-sp.json")).unwrap())
            .unwrap();
    let sm = saved["messages"].as_array().unwrap();
    for id in ["call_1", "call_2"] {
        let n = sm.iter().filter(|m| m["tool_call_id"] == id).count();
        assert_eq!(n, 1, "{id} 的 tool 回复必须恰好一条，多一条少一条下次请求就是 400");
    }
    assert_eq!(sm.last().unwrap()["role"], "user", "中断要成为数据，得留一条模型看得见的记录");
    assert!(sm.last().unwrap()["content"].as_str().unwrap().contains("按了停止"));

    let _ = std::fs::remove_dir_all(&root);
}

/// 刚发出去就按停止：清旗与置 running 在同一把锁里，所以这一步之后立的旗不会被抹掉。
#[test]
fn stop_flag_survives_begin_and_is_cleared_on_next_run() {
    let (port, _) = fake_gateway(vec!["x".into()]);
    let root = temp_root("flag", port);
    let app = App::at(root.clone());
    app.open_for_test("e2e");
    app.request_stop("e2e");
    assert!(app.try_begin("e2e"), "占坑要成功");
    assert!(!app.stopped("e2e"), "新一轮开始时清掉上一轮的停止旗");
    app.request_stop("e2e");
    assert!(app.stopped("e2e"));
    app.end_run("e2e");
    assert!(app.stopped("e2e"), "结束不清旗：Kotlin 把它留给 RunLedger 记账，下一轮 beginRun 才清");
    let _ = std::fs::remove_dir_all(&root);
}

/// auto 档写一个**新建**的文件：低危 ⇒ 不打扰人，但文件、diff、检查点三样都得留下。
#[test]
fn auto_mode_write_lands_the_file_the_diff_and_the_checkpoint() {
    use serde_json::json;
    let rsp1 = tool_calls_frame(&[("call_w", "write", json!({"path": "src/new.md", "content": "# 新\n"}))]);
    let (port, seen) = scripted_gateway(vec![rsp1, final_frame("写好了")]);
    let (root, app) = app_with_session("wr", port, "w", "auto");

    let (tx, rx) = std::sync::mpsc::channel::<String>();
    app.bus.lock().unwrap().push(tx);
    engine::turn(&app, "w", "建个文件").expect("回合应跑完");

    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-w.json")).unwrap()).unwrap();
    let tm = saved["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["role"] == "tool")
        .expect("write 之后必须有一条 tool 回复");
    let f = root.join("ws/src/new.md");
    assert!(f.is_file(), "低危的新建文件在 auto 档就该直接写成；工具回的是 {}", tm["content"]);
    assert_eq!(std::fs::read_to_string(&f).unwrap(), "# 新\n");
    assert_eq!(app.approvals.size(), 0, "不该留下没收摊的等待");

    let frames: Vec<String> = {
        let mut v = Vec::new();
        while let Ok(x) = rx.try_recv() {
            v.push(x);
        }
        v
    };
    let done = frames.iter().find(|f| f.contains(r##""card":"diff""##)).expect("写改类要按 diff 卡片渲染");
    assert!(done.contains("+1 行"), "{done}");

    // 会话文件里：给模型的那句只有"已写入 …"，diff 单独一个键 —— 几百行 diff 塞进上下文是纯浪费
    assert!(tm["content"].as_str().unwrap().starts_with("已写入 src/new.md"), "{tm}");
    // diff 的头用的是 U+2212 那个减号，不是 ASCII 的 `-`：抄错一个字符界面对不上
    assert!(tm["diff"].as_str().unwrap().contains("−1 行 / +1 行"), "{tm}");
    let reqs = seen.lock().unwrap().clone();
    assert!(!reqs[1].contains("−1 行 / +1 行"), "diff 不该混进发给模型的 tool 内容");

    // 检查点账本：这轮动了哪个文件、改之前有没有快照（账本在状态根，不在工作区）
    let ledger = std::fs::read_to_string(root.join("checkpoints.jsonl")).unwrap();
    assert!(ledger.contains(r#""path":"src/new.md""#), "{ledger}");
    // 新建的文件没有"上一版"，所以 snap 是空的 —— 回滚据此该删而不是还原
    assert!(ledger.contains(r##""snap":""##), "新建的文件不该假装有一份快照：{ledger}");
    let _ = std::fs::remove_dir_all(&root);
}

/// ask 档改一个**已存在**的文件：必须弹卡、必须能在刷新后还看得见这张卡、
/// 而人拒了就必须一个字都不写。
#[test]
fn ask_mode_blocks_the_write_until_the_card_is_answered() {
    use serde_json::json;
    let rsp1 = tool_calls_frame(&[(
        "call_e",
        "edit",
        json!({"path": "src/a.rs", "old_string": "line2", "new_string": "LINE-2"}),
    )]);
    let (port, _seen) = scripted_gateway(vec![rsp1, final_frame("改好了")]);
    let (root, app) = app_with_session("ask", port, "k", "ask");

    let h = {
        let app = Arc::clone(&app);
        thread::spawn(move || engine::turn(&app, "k", "把第二行改掉"))
    };

    // 卡出生 → 先看 /api/state 的投影。这条断言钉的是那个真出过的事故：
    // 卡只随 SSE 流一次，页面一刷新引擎还卡在等人点一个不会再出现的按钮，
    // 表现是"agent 莫名卡死"，最后超时自动拒掉。
    let mut id = String::new();
    for _ in 0..400 {
        if let Some((i, w)) = app.approvals.rows().first() {
            id = i.clone();
            assert_eq!(w.sid, "k");
            let json = serde_json::from_str::<Value>(&w.payload).unwrap();
            assert_eq!(json["risk"], "mid", "覆盖已有文件是中危，不是高危也不是低危：{json}");
            assert_eq!(json["kind"], "write");
            assert_eq!(json["tool"], "write");
            assert_eq!(json["pattern"], "src/a.rs");
            assert!(json["riskWhy"].as_str().unwrap().contains("已存在"), "{json}");

            let sf = app.store.restore("k");
            let pending = app.approvals.pending_for("k");
            let view = crate::state::View {
                settings: &app.settings,
                session: sf.as_ref(),
                session_id: "k",
                store: &app.store,
                running: true,
                usage: (0, 0),
                pending: &pending,
            };
            let st: Value = serde_json::from_str(&crate::state::state_json(&view)).unwrap();
            let rows = st["pending"].as_array().unwrap();
            assert_eq!(rows.len(), 1, "挂着没答的卡要交出去，不然刷新就看不见它了：{rows:?}");
            assert_eq!(rows[0]["ev"], "approval");
            assert_eq!(rows[0]["data"]["id"], id, "投影里的载荷要带 id，前端才知道答哪一张");
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    assert!(!id.is_empty(), "ask 档没弹卡");
    let before = std::fs::read_to_string(root.join("ws/src/a.rs")).unwrap();

    // 拒绝：一个字都不许写
    app.approvals.complete(&id, "deny");
    h.join().unwrap().expect("回合应跑完");
    assert_eq!(std::fs::read_to_string(root.join("ws/src/a.rs")).unwrap(), before, "拒了还写就是越权");

    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-k.json")).unwrap()).unwrap();
    let tm = saved["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["role"] == "tool")
        .expect("被拒也要有一条 tool 回复");
    assert!(tm["content"].as_str().unwrap().contains("用户拒绝了这次"), "{tm}");
    assert_eq!(tm["note"], "已拒绝", "结论要挂在紧接着那条工具消息上，刷新后还查得到");
    let _ = std::fs::remove_dir_all(&root);
}

/// 同一张卡的第二次答复必须无效，而引擎拿到的必须是第一个 —— 两条矛盾的结论都不能接受。
#[test]
fn allow_once_answer_lets_the_edit_through_and_remembers_the_verdict() {
    use serde_json::json;
    let rsp1 = tool_calls_frame(&[(
        "call_e",
        "edit",
        json!({"path": "src/a.rs", "old_string": "line2", "new_string": "LINE-2"}),
    )]);
    let (port, _seen) = scripted_gateway(vec![rsp1, final_frame("改好了")]);
    let (root, app) = app_with_session("ok", port, "y", "ask");
    let h = {
        let app = Arc::clone(&app);
        thread::spawn(move || engine::turn(&app, "y", "把第二行改掉"))
    };
    let mut id = String::new();
    for _ in 0..400 {
        if let Some((i, _)) = app.approvals.rows().first() {
            id = i.clone();
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    app.approvals.complete(&id, "allow_once");
    app.approvals.complete(&id, "deny"); // 手慢的人再点一次
    h.join().unwrap().expect("回合应跑完");
    let now = std::fs::read_to_string(root.join("ws/src/a.rs")).unwrap();
    assert!(now.contains("LINE-2"), "第一个答复是允许，就该写下去：{now}");
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("sessions/pc-y.json")).unwrap()).unwrap();
    let tm = saved["messages"].as_array().unwrap().iter().find(|m| m["role"] == "tool").unwrap();
    assert_eq!(tm["note"], "允许一次");
    // 改坏了要能回滚：快照必须在写之前留下，且是**改之前**那份内容
    let snaps: Vec<_> = std::fs::read_dir(root.join("ws/.haoai-snap"))
        .unwrap()
        .filter_map(|e| e.ok())
        .collect();
    assert_eq!(snaps.len(), 1, "应留下一份快照：{snaps:?}");
    assert_eq!(std::fs::read_to_string(snaps[0].path()).unwrap(), "line1\nline2\nline3\n");
    let _ = std::fs::remove_dir_all(&root);
}
