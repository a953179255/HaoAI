use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::sync::mpsc::channel;
use std::sync::Arc;
use std::time::Duration;

use crate::engine;
use crate::state::{self, View};
use crate::store::SessionFile;
use crate::App;

// UI 资源直接从 pc/ 的那一份读进来编译期内嵌：网页壳用的永远是同一个文件，
// 不出现"Rust 里嵌的 UI 落后于 Kotlin 打包的 UI"这种两边各改一半的状态。
const INDEX_HTML: &str = include_str!("../../../pc/src/main/resources/ui/index.html");
const MD_JS: &str = include_str!("../../../pc/src/main/resources/ui/md.js");
const SHARED_JS: &str = include_str!("../../../pc/src/main/resources/ui/shared.js");

pub fn handle(stream: TcpStream, app: Arc<App>) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    // BufReader 会按 8KB 预读：一旦用它读过头，**就必须继续用它读 body**，
    // 否则 POST 的请求体已经被吞进 BufReader 的内部缓冲，再从裸 stream 上读会永远等不到
    // （GET 没有 body，所以这个坑只在带体的请求上暴露）。
    let mut br = BufReader::new(stream);

    let mut head: Vec<u8> = Vec::new();
    loop {
        match br.read_until(b'\n', &mut head) {
            Ok(0) => return,
            Ok(_) => {
                if head.ends_with(b"\r\n\r\n") {
                    break;
                }
                if head.len() > 65_536 {
                    return;
                }
            }
            Err(_) => return,
        }
    }

    let text = String::from_utf8_lossy(&head).to_string();
    let mut lines = text.split_whitespace();
    let _method = lines.next().unwrap_or("GET").to_string();
    let target = lines.next().unwrap_or("/").to_string();
    let content_length = text
        .lines()
        .find(|l| l.split(':').next().map(|k| k.eq_ignore_ascii_case("Content-Length")).unwrap_or(false))
        .and_then(|l| l.split_once(':').map(|(_, v)| v.trim().parse::<usize>().unwrap_or(0)))
        .unwrap_or(0);
    // Body 必须在处理开头一次性读完：请求体是流，读第二次就是空的（Kotlin 那边因为
    // 每个字段各读一次，decide 的三个字段只有第一个拿得到值，"允许一次"直接落进拒绝分支）
    let mut body = String::new();
    if content_length > 0 {
        let mut buf = vec![0u8; content_length.min(1_048_576)];
        if br.read_exact(&mut buf).is_ok() {
            body = String::from_utf8_lossy(&buf).to_string();
        }
    }
    let stream = br.into_inner();

    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p, q.to_string()),
        None => (target.as_str(), String::new()),
    };

    // 只按 path 分发、不看 method —— 与 `Server.route` 的 `when(path)` 逐字对齐。
    match path {
        "/" | "/index.html" => send(stream, 200, "text/html; charset=utf-8", INDEX_HTML),
        "/md.js" => send(stream, 200, "text/javascript; charset=utf-8", MD_JS),
        "/shared.js" => send(stream, 200, "text/javascript; charset=utf-8", SHARED_JS),
        "/api/state" => {
            let sid = query_of(&query, "sid");
            let (code, ctype, b) = state_body(&app, &sid);
            send(stream, code, ctype, &b)
        }
        "/api/sessions" => send(stream, 200, "application/json; charset=utf-8", &sessions_body(&app)),
        "/api/open" => {
            let (code, ctype, b) = open_session(&app, &body);
            send(stream, code, ctype, &b)
        }
        "/api/task" => {
            let (code, b) = start_task(&app, &body);
            send(stream, code, "application/json; charset=utf-8", &b)
        }
        "/api/stop" => {
            let b = stop_task(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/decide" => {
            decide(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", r#"{"ok":true}"#)
        }
        "/api/events" => sse(stream, app),
        _ => send(stream, 404, "text/plain; charset=utf-8", "not found"),
    }
}

fn query_of(query: &str, key: &str) -> String {
    query
        .split('&')
        .find_map(|kv| kv.strip_prefix(&format!("{key}=")))
        .unwrap_or("")
        .to_string()
}

fn body_str(body: &str, key: &str) -> String {
    serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| v.get(key).and_then(|x| x.as_str()).map(String::from))
        .unwrap_or_default()
}

/// 复刻 `pick(sid)` + `sessions[id]`：给了 sid 就用它，否则用当前会话；
/// 只有**已驻留**的会话才拿得出完整状态（`stateJson` 不懒加载）。
fn state_body(app: &App, sid: &str) -> (u16, &'static str, String) {
    let id = {
        let cur = app.current.lock().unwrap_or_else(|p| p.into_inner());
        if sid.is_empty() {
            cur.clone()
        } else {
            sid.to_string()
        }
    };
    let resident = app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&id);
    let mut sf: Option<SessionFile> = if resident { app.store.restore(&id) } else { None };
    // 档位报的是**活值**：按过"本任务都允许"之后界面该显示 auto，而不是文件里那个 ask。
    if let Some(s) = sf.as_mut() {
        s.meta.mode = app.mode_of(&id, &s.meta.mode);
    }
    let pending = app.approvals.pending_for(&id);
    let view = View {
        settings: &app.settings,
        session: sf.as_ref(),
        session_id: &id,
        store: &app.store,
        running: app.is_running(&id),
        usage: app.usage_of(&id),
        pending: &pending,
    };
    (200, "application/json; charset=utf-8", state::state_json(&view))
}

fn sessions_body(app: &App) -> String {
    let cur = app.current.lock().unwrap_or_else(|p| p.into_inner()).clone();
    // ?q= 搜索依赖 SessionIndex.match（要读引擎内历史），M1 先不接，返回全量列表
    state::sessions_json(&app.store.list(200), &cur)
}

fn open_session(app: &App, body: &str) -> (u16, &'static str, String) {
    let id = body_str(body, "id");
    let meta = app.store.list(400).into_iter().find(|m| m.id == id);
    let Some(m) = meta else {
        return (404, "application/json; charset=utf-8", r#"{"ok":false,"error":"没有这个会话"}"#.to_string());
    };
    {
        let mut res = app.resident.lock().unwrap_or_else(|p| p.into_inner());
        res.insert(id.clone());
    }
    {
        let mut cur = app.current.lock().unwrap_or_else(|p| p.into_inner());
        *cur = id.clone();
    }
    app.publish(&m.id, "opened", &format!("{{\"id\":{},\"title\":{},\"mode\":{}}}", state::quote(&m.id), state::quote(&m.title), state::quote(&m.mode)));
    (200, "application/json; charset=utf-8", format!("{{\"ok\":true,\"id\":{}}}", state::quote(&id)))
}

/// `POST /api/task` {text, sid}。受理即回 `{"ok":true,"sid":…}`，
/// 真正的输出走 `/api/events` 的 SSE —— 与 Kotlin 一样"响应先回、事件后推"，
/// 这样前端不用为一个回合挂住一条连接 8 分钟。
fn start_task(app: &Arc<App>, body: &str) -> (u16, String) {
    let text = body_str(body, "text");
    if text.trim().is_empty() {
        return (200, r#"{"ok":false}"#.to_string());
    }
    let want = body_str(body, "sid");
    let sid = if want.is_empty() {
        app.current.lock().unwrap_or_else(|p| p.into_inner()).clone()
    } else {
        want
    };
    if sid.is_empty() {
        return (409, format!(r#"{{"ok":false,"error":{}}}"#, state::quote("还没有会话，先新建一条")));
    }
    // 未驻留的先按 /api/open 的语义拉起来，否则回合找不到历史
    if !app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid) {
        if app.store.meta(&sid).is_none() {
            return (409, format!(r#"{{"ok":false,"error":{}}}"#, state::quote("没有这个会话")));
        }
        app.resident.lock().unwrap_or_else(|p| p.into_inner()).insert(sid.clone());
    }
    match engine::begin(Arc::clone(app), sid.clone(), text) {
        Ok(()) => (200, format!(r#"{{"ok":true,"sid":{}}}"#, state::quote(&sid))),
        Err(e) => (409, format!(r#"{{"ok":false,"error":{}}}"#, state::quote(&e))),
    }
}

/// `POST /api/stop` {sid} —— 停止**指定会话**（默认当前那条）的任务。
///
/// 两件必须一起做的事：
/// 1. 置旗（引擎只在回合边界与工具边界看它，所以是**收尾式**停止，不是掐断 HTTP 流）；
/// 2. **把挂着的审批与提问一次性判掉**。漏了这条，按了停止引擎还卡在等一个不会来的点击，
///    "停止"按钮就成了它自己要中止的那件事的受害者。
/// 这里刻意**不去抢**跑任务的那把锁：抢了就等于"要停止必须先等本轮跑完"。
fn stop_task(app: &App, body: &str) -> String {
    let sid = pick_sid(app, body);
    // 审批给 deny（fail-closed），提问给空串（"用户没回答"）；收摊在等待方自己那里
    let aborted = if sid.is_empty() { Default::default() } else { app.approvals.abort(&sid) };
    if !sid.is_empty() {
        app.request_stop(&sid);
    }
    let running = !sid.is_empty() && app.is_running(&sid);
    let text = if running || aborted.total() > 0 {
        // Kotlin 那句是拼出来的：拒了 N 个就把它写进括号里，别说空话
        let tail = if aborted.approvals > 0 {
            format!("（顺手拒掉 {} 个待确认）", aborted.approvals)
        } else {
            String::new()
        };
        format!("已请求停止{tail}，正在收尾…")
    } else {
        "这条会话现在没有正在跑的任务。".to_string()
    };
    app.publish(&sid, "notice", &state::quote(&text));
    format!(r#"{{"ok":true,"pending":{}}}"#, aborted.total())
}

/**
 * `POST /api/decide` {id, decision, answer} —— 回答一张挂着的卡（网页与手机同一个口）。
 *
 * `answer` 非空时优先（提问的自由输入），否则用 `decision`
 * （审批那几个按钮：allow_once / allow_session / allow_rule / deny）。
 * `allow_session` 会把**发起这条审批的那条会话**切成 auto —— 按等待里记的 sid 找，
 * 找不到才退回当前会话： stale 的答复不该静默无效，也不该把别的会话一起放开了。
 */
fn decide(app: &App, body: &str) {
    let id = body_str(body, "id");
    let decision = body_str(body, "decision");
    let answer = body_str(body, "answer");
    // isAsk/sid 都在答复**之前**由状态机取好（之后它可能已销号，就读不到了）
    let done = app.approvals.complete(&id, if answer.is_empty() { &decision } else { &answer });
    if decision == "allow_session" {
        let sid = if done.sid.is_empty() {
            app.current.lock().unwrap_or_else(|p| p.into_inner()).clone()
        } else {
            done.sid.clone()
        };
        app.set_mode(&sid, "auto");
        app.publish(&sid, "mode", r#"{"mode":"auto"}"#);
    }
}

/// `pick(sid)`：给了就用它，否则用当前会话。
fn pick_sid(app: &App, body: &str) -> String {
    let want = body_str(body, "sid");
    if want.is_empty() {
        app.current.lock().unwrap_or_else(|p| p.into_inner()).clone()
    } else {
        want
    }
}

/// no-store 不是可选项——2026-10-05 实测过没有它时浏览器启发式缓存旧 index.html，
/// 更新后用户点开的还是旧页面，症状是"修了没用"。
fn send(mut stream: TcpStream, code: u16, ctype: &str, body: &str) {
    let bytes = body.as_bytes();
    let head = format!(
        "HTTP/1.1 {code} {reason}\r\nContent-Type: {ctype}\r\nCache-Control: no-store\r\n\
         Content-Length: {len}\r\nConnection: close\r\n\r\n",
        reason = reason(code),
        len = bytes.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(bytes);
    let _ = stream.flush();
}

fn reason(code: u16) -> &'static str {
    match code {
        200 => "OK",
        404 => "Not Found",
        _ => "Internal Server Error",
    }
}

/// `/api/events`：Kotlin 侧走 `sendResponseHeaders(200, 0)`，那是分块传输，
/// 所以这里也必须用 chunked，否则浏览器拿不到增量事件。
fn sse(mut stream: TcpStream, app: Arc<App>) {
    let _ = stream.set_read_timeout(None);
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n\
                Cache-Control: no-cache\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
    if stream.write_all(head.as_bytes()).is_err() {
        return;
    }
    let _ = stream.flush();

    let (tx, rx) = channel::<String>();
    app.bus.lock().unwrap_or_else(|p| p.into_inner()).push(tx);

    if chunk(&mut stream, &state::sse_frame("hello", "{}", "")).is_err() {
        return;
    }
    loop {
        // 15s 一次 ping，与 Server.sse 的 Thread.sleep(15_000) 同节奏；
        // 期间有 publish 就立刻插一帧。
        match rx.recv_timeout(Duration::from_secs(15)) {
            Ok(frame) => {
                if chunk(&mut stream, &frame).is_err() {
                    break;
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if chunk(&mut stream, &state::sse_frame("ping", "{}", "")).is_err() {
                    break;
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
}

fn chunk(stream: &mut TcpStream, body: &str) -> std::io::Result<()> {
    stream.write_all(format!("{:x}\r\n", body.len()).as_bytes())?;
    stream.write_all(body.as_bytes())?;
    stream.write_all(b"\r\n")?;
    stream.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::net::TcpListener;
    use std::thread;

    fn test_app(dir: &std::path::Path) -> Arc<App> {
        App::at(dir.to_path_buf())
    }

    /// 钉住那个 BufReader 坑：**带请求体的 POST 必须能拿到 body**。
    /// GET 没有体，所以只测 GET 的话这个 bug 会一路溜到真机上。
    #[test]
    fn post_with_body_reaches_handler() {
        let dir = std::env::temp_dir().join(format!("haoai-m1-{}", std::process::id()));
        let _ = fs::create_dir_all(dir.join("sessions"));
        fs::write(
            dir.join("sessions/pc-t1.json"),
            r#"{"id":"t1","title":"测试会话","workspace":"W","mode":"ask","messages":[{"role":"user","content":"你好"}]}"#,
        )
        .unwrap();

        let app = test_app(&dir);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        {
            let app = Arc::clone(&app);
            thread::spawn(move || {
                for s in listener.incoming() {
                    if let Ok(s) = s {
                        handle(s, Arc::clone(&app));
                    }
                }
            });
        }

        let mut c = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let body = r#"{"id":"t1"}"#;
        write!(
            c,
            "POST /api/open HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
            body.len()
        )
        .unwrap();
        let mut buf = String::new();
        c.read_to_string(&mut buf).unwrap();
        assert!(buf.starts_with("HTTP/1.1 200 OK"), "响应异常：{buf}");
        assert!(buf.ends_with(r#"{"ok":true,"id":"t1"}"#), "body 没被读到：{buf}");

        // 驻留之后，该会话的 state 必须带出真实标题与消息
        let mut c2 = TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(c2, "GET /api/state?sid=t1 HTTP/1.1\r\nHost: x\r\n\r\n").unwrap();
        let mut b2 = String::new();
        c2.read_to_string(&mut b2).unwrap();
        assert!(b2.contains(r#""title":"测试会话""#), "open 之后 state 没实值：{b2}");
        assert!(b2.contains(r#""content":"你好""#));

        let _ = fs::remove_dir_all(&dir);
    }
}
