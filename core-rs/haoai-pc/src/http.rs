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
        "/api/presets" => {
            let b = crate::catalog::presets_json(&app.store.home);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/skills" => {
            let b = crate::catalog::skills_json(&app.store.home);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/teams" => {
            let b = crate::catalog::teams_json(&app.store.home);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/new" => {
            let b = new_session(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/mode" => {
            let b = mode_route(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/open" => {
            let (code, ctype, b) = open_session(&app, &body);
            send(stream, code, ctype, &b)
        }
        "/api/rename" => {
            let (code, b) = rename_session(&app, &body);
            send(stream, code, "application/json; charset=utf-8", &b)
        }
        "/api/delete" => {
            let (code, b) = delete_session(&app, &body);
            send(stream, code, "application/json; charset=utf-8", &b)
        }
        "/api/pin" => {
            let b = pin_session(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/trash" => {
            let b = trash_body(&app);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/untrash" => {
            let b = untrash(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/purge" => {
            let ok = app.store.purge(&body_str(&body, "name"));
            send(stream, 200, "application/json; charset=utf-8", &format!("{{\"ok\":{ok}}}"))
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
        .and_then(|v| v.get(key).map(primitive_content))
        .unwrap_or_default()
}

/// `Body.str(key) = obj[key]?.jsonPrimitive?.contentOrNull ?: ""` 的口径：
/// 数字与布尔取的是**字面文本**（`5`、`true`），不是"只有字符串才有值"。
/// 前端把 `index` 当数字发（`{"index":5}`）时，按 as_str 取就成了空串，
/// "删到第几条"会静默变成"删不掉"。null 与非原始类型都算空。
fn primitive_content(x: &serde_json::Value) -> String {
    match x {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::Bool(b) => b.to_string(),
        _ => String::new(),
    }
}

/// 复刻 `pick(sid)` + `sessions[id]`：给了 sid 就用它，否则用当前会话；
/// 只有**已驻留**的会话才拿得出完整状态（`stateJson` 不懒加载）。
fn state_body(app: &App, sid: &str) -> (u16, &'static str, String) {
    let id = if sid.is_empty() { app.current_id() } else { sid.to_string() };
    let resident = app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&id);
    let mut sf: Option<SessionFile> = if resident { app.store.restore(&id) } else { None };
    // 档位报的是**活值**：按过"本任务都允许"之后界面该显示 auto，而不是文件里那个 ask。
    // 标题同理 —— Kotlin 那边读的是 `engine.session.title`，改名与自动取名都先动内存再落盘。
    if let Some(s) = sf.as_mut() {
        s.meta.mode = app.mode_of(&id, &s.meta.mode);
        if let Some(t) = app.title_of(&id) {
            s.meta.title = t;
        }
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
    let cur = app.current_id();
    // ?q= 搜索依赖 SessionIndex.match（要读引擎内历史），M1 先不接，返回全量列表
    state::sessions_json(&app.store.list(200), &cur)
}

fn open_session(app: &App, body: &str) -> (u16, &'static str, String) {
    let id = body_str(body, "id");
    let meta = app.store.list(400).into_iter().find(|m| m.id == id);
    let Some(m) = meta else {
        return (404, "application/json; charset=utf-8", r#"{"ok":false,"error":"没有这个会话"}"#.to_string());
    };
    app.touch(&id);
    // 等价于 `touch(id)` 里建引擎那一步：`SessionIndex.restore` 会把标题、档位从文件搬进内存
    app.set_title(&id, &m.title);
    let live_mode = app.mode_of(&id, &m.mode);
    app.publish(
        &m.id,
        "opened",
        &format!(
            "{{\"id\":{},\"title\":{},\"mode\":{}}}",
            state::quote(&m.id),
            state::quote(&m.title),
            state::quote(&live_mode)
        ),
    );
    (200, "application/json; charset=utf-8", format!("{{\"ok\":true,\"id\":{}}}", state::quote(&id)))
}

/**
 * `POST /api/new` {ws?, preset?, team?} —— 开一条新会话（或复用眼前那条空的）。
 *
 * 两条判据照 Kotlin 抄，各自都是踩过的坑：
 * 1. **眼前那条还一条没说过就复用它**：一路点"新任务"会在磁盘上留一堆空 `pc-*.json`，
 *    侧栏被自己几分钟前的手滑刷屏。
 * 2. **点了目录就一定新建**：复用手边那条空的会把它悄悄挪到别的目录，用户下一句话
 *    就落到了他没选的地方；目录打不开要明确报错，**不退回默认工作区** ——
 *    那等于把人送进一个他没选的仓库里写文件。
 *
 * 档位必须回给前端：新会话的档位**继承全局默认**，不是上一条会话的档位。
 * 只回 id 的话前端会沿用界面上那份旧档位显示（Kotlin 那条注释的原话：全局是 ask，
 * 新建的会话顶上却亮着"自动"，用户以为自己在问模式下让它自动写了文件）。
 */
fn new_session(app: &App, body: &str) -> String {
    let want_ws = body_str(body, "ws");
    let preset = body_str(body, "preset");
    let team = body_str(body, "team");

    // 角色卡与团队的数据层还没搬。**不能静默建一条没有角色的会话**然后让"我选的卡
    // 没生效"藏起来 —— Kotlin 那边 `Presets.usable` 挡的就是这件事。
    if !team.is_empty() {
        return err_json("团队会话还没搬过来（/api/teams 尚未实现），先建一条普通会话");
    }
    if !preset.is_empty() {
        return err_json("角色卡还没搬过来（/api/presets 尚未实现），先建一条没有角色的会话");
    }

    if want_ws.is_empty() {
        if let Some((id, title, mode)) = idle_current(app) {
            return created(app, &id, &title, &mode, true, false);
        }
    }
    let ws = if want_ws.is_empty() { app.settings.workspace_file() } else { want_ws.clone() };
    if !want_ws.is_empty() && !std::path::Path::new(&ws).is_dir() {
        return err_json(&format!("这个目录打不开：{ws}"));
    }
    let id = app.store.create(&ws, &app.settings.permission_mode, &app.settings);
    // 指定了目录那一支带 role 键、没指定那一支不带 —— 两边形状不一样是 Kotlin 原样，
    // 前端读的是 `d.role||''`，少一个键不报错，但要对齐就对齐到底
    created(app, &id, "新会话", &app.settings.permission_mode, false, !want_ws.is_empty())
}

/// 眼前那条是空的就复用它：**已驻留**（不驻留的那条在 Kotlin 里压根没有引擎可问）、
/// 没在跑、没消息、没待办。
fn idle_current(app: &App) -> Option<(String, String, String)> {
    let cur = app.current_id();
    if cur.is_empty() || app.is_running(&cur) {
        return None;
    }
    if !app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&cur) {
        return None;
    }
    let sf = app.store.restore(&cur)?;
    if !sf.msgs.is_empty() || !sf.todos.is_empty() {
        return None;
    }
    Some((cur.clone(), sf.meta.title.clone(), app.mode_of(&cur, &sf.meta.mode)))
}

/// 驻留 + 切成当前 + 两条广播。Kotlin 的 `touch(id)` 就是这个效果：
/// `currentId()` = **最近 touch 过的那条**，所以新建完它自动就是当前。
fn created(app: &App, id: &str, title: &str, mode: &str, reused: bool, with_role: bool) -> String {
    app.touch(id);
    // 新建的那条在 Kotlin 那边是 `Session(...)` 的默认标题"新会话"，复用那条就是文件里那份；
    // 两种都要种进活标题表，否则第一次 persist 会把内存里没有的那份当成不动。
    app.set_title(id, title);
    let opened = if with_role {
        format!(
            "{{\"id\":{},\"title\":{},\"mode\":{},\"role\":\"\"}}",
            state::quote(id),
            state::quote(title),
            state::quote(mode)
        )
    } else {
        format!(
            "{{\"id\":{},\"title\":{},\"mode\":{}}}",
            state::quote(id),
            state::quote(title),
            state::quote(mode)
        )
    };
    app.publish(id, "opened", &opened);
    app.publish(id, "sessions", "{}");
    if with_role {
        format!(
            "{{\"ok\":true,\"id\":{},\"mode\":{},\"role\":\"\",\"reused\":{}}}",
            state::quote(id),
            state::quote(mode),
            reused
        )
    } else {
        format!(
            "{{\"ok\":true,\"id\":{},\"mode\":{},\"reused\":{}}}",
            state::quote(id),
            state::quote(mode),
            reused
        )
    }
}

fn err_json(msg: &str) -> String {
    format!("{{\"ok\":false,\"error\":{}}}", state::quote(msg))
}

/**
 * `POST /api/rename` {id,title} —— 改会话标题。
 *
 * **内存里那份也要改**，否则下一回合结束 `persist()` 会把新标题又写回旧的。
 * 两边清得还不一样：文件里换行被换成空格，内存里只 trim 不换行（Kotlin 原文如此），
 * 于是带换行的标题在下一回合落盘时会把空格又变回换行 —— 照抄，不顺手"修好"。
 */
/// （`pub(crate)` 是为了让 e2e 能演"改名之后还能不能被下一回合盖回去"——
/// 直接调 `store.rename` 会绕过活标题那一步，而那条恰恰是这个路由存在的理由。）
pub(crate) fn rename_session(app: &App, body: &str) -> (u16, String) {
    let id = body_str(body, "id");
    let title = body_str(body, "title");
    let ok = app.store.rename(&id, &title);
    if ok {
        let live = crate::utf16::utf16_take(&crate::store::ktrim(&title).to_string(), 60).to_string();
        app.set_title(&id, &live);
        app.publish(&id, "sessions", "{}");
    }
    (if ok { 200 } else { 404 }, format!("{{\"ok\":{ok}}}"))
}

/**
 * `POST /api/delete` {id} —— 删会话（实为移进 `sessions/.trash`）。
 *
 * 一条硬约束：**跑着的会话不许删**。否则线程会在一个已经被移走的文件上
 * 继续 `persist()`，把文件又写回来，表现为"删了又出现"。
 */
fn delete_session(app: &App, body: &str) -> (u16, String) {
    let id = body_str(body, "id");
    if !id.is_empty() && app.is_running(&id) {
        return (409, err_json("这个会话正在跑，先停止再删"));
    }
    let ok = app.store.delete(&id);
    if ok {
        app.forget(&id);
        app.publish("", "sessions", "{}");
    }
    (if ok { 200 } else { 404 }, format!("{{\"ok\":{ok}}}"))
}

/// `POST /api/pin` {id,pinned} —— 置顶 / 取消置顶。
fn pin_session(app: &App, body: &str) -> String {
    let id = body_str(body, "id");
    let p = body_str(body, "pinned");
    let on = p == "true" || p == "1";
    let ok = app.store.pin(&id, on);
    if ok {
        app.publish(&id, "sessions", "{}");
        return r#"{"ok":true}"#.to_string();
    }
    err_json("没有这条会话")
}

/// `GET /api/trash` —— 回收站列表（删除其实是移进去的，所以必须给一个入口，
/// 否则"可恢复"等于没有：用户删错一条只能自己去开文件管理器）。
fn trash_body(app: &App) -> String {
    let rows: Vec<String> = app
        .store
        .list_trash(60)
        .iter()
        .map(|r| {
            format!(
                "{{\"name\":{},\"id\":{},\"title\":{},\"messages\":{},\"updated\":{},\"bytes\":{}}}",
                state::quote(&r.name),
                state::quote(&r.meta.id),
                state::quote(&r.meta.title),
                r.meta.messages,
                r.meta.updated,
                r.bytes
            )
        })
        .collect();
    format!("[{}]", rows.join(","))
}

/// `POST /api/untrash` {name} —— 把一条会话放回历史列表。
fn untrash(app: &App, body: &str) -> String {
    let name = body_str(body, "name");
    match app.store.untrash(&name) {
        Ok(id) => {
            app.publish("", "sessions", "{}");
            format!("{{\"ok\":true,\"id\":{}}}", state::quote(&id))
        }
        Err(e) => err_json(&e),
    }
}

/**
 * `POST /api/mode` {mode, sid} —— 切档位。
 *
 * 这条是**安全相关**的：界面把「计划」点亮而引擎没跟着换，用户看到的是只读、
 * 跑的却是自动 —— 静默失效的计划模式比没有计划模式更糟（真浏览器点过一次才看出来）。
 *
 * `now` 的取法照 Kotlin：驻留的会话取它引擎里的**活档位**，没驻留的取**全局默认**
 * （不是会话文件里那个）—— 两端"当前档位"的语义必须一致。
 */
fn mode_route(app: &App, body: &str) -> String {
    let m = body_str(body, "mode");
    let sid = pick_sid(app, body);
    let resident =
        !sid.is_empty() && app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid);
    let now = if resident {
        let file_mode = app
            .store
            .meta(&sid)
            .map(|mt| mt.mode)
            .unwrap_or_else(|| app.settings.permission_mode.clone());
        app.mode_of(&sid, &file_mode)
    } else {
        app.settings.permission_mode.clone()
    };

    if !matches!(m.as_str(), "plan" | "ask" | "auto") {
        return format!("{{\"ok\":true,\"mode\":{}}}", state::quote(&now));
    }
    // sid 空就用当前；连当前都没有（全新状态根）就不动 —— 造会话是 `/api/new` 的活
    let target = if sid.is_empty() { app.current_id() } else { sid.clone() };
    if target.is_empty() {
        return format!("{{\"ok\":true,\"mode\":{}}}", state::quote(&now));
    }
    app.touch(&target);
    app.set_mode(&target, &m);
    app.publish(&target, "mode", &format!("{{\"mode\":{}}}", state::quote(&m)));
    format!("{{\"ok\":true,\"mode\":{}}}", state::quote(&m))
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
    let sid = if want.is_empty() { app.current_id() } else { want };
    if sid.is_empty() {
        return (409, format!(r#"{{"ok":false,"error":{}}}"#, state::quote("还没有会话，先新建一条")));
    }
    // 未驻留的先按 /api/open 的语义拉起来，否则回合找不到历史
    if !app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid)
        && app.store.meta(&sid).is_none()
    {
        return (409, format!(r#"{{"ok":false,"error":{}}}"#, state::quote("没有这个会话")));
    }
    // 开跑就把这条挪到最近表最前（`startRun` 里的 `touch(target)`）：
    // 并行几条时"没给 sid 的下一句"该落在刚刚说过话的那条上
    app.touch(&sid);
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
        let sid = if done.sid.is_empty() { app.current_id() } else { done.sid.clone() };
        app.set_mode(&sid, "auto");
        app.publish(&sid, "mode", r#"{"mode":"auto"}"#);
    }
}

/// `pick(sid)`：给了就用它，否则用当前会话。
fn pick_sid(app: &App, body: &str) -> String {
    let want = body_str(body, "sid");
    if want.is_empty() { app.current_id() } else { want }
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
    use serde_json::Value;
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

    fn new_json(app: &Arc<App>, body: &str) -> (String, String) {
        let out = new_session(app, body);
        let id = serde_json::from_str::<serde_json::Value>(&out)
            .ok()
            .and_then(|v| v.get("id").and_then(|x| x.as_str()).map(String::from))
            .unwrap_or_default();
        (id, out)
    }

    /// `/api/new` 的两条判据：**空的当前会话要复用**（否则侧栏被自己的手滑刷屏）、
    /// **点了目录必须新建**（否则会话被悄悄挪到别的目录），以及**目录打不开要明确报错**
    /// （退回默认工作区 = 把人送进一个他没选的仓库里写文件）。
    #[test]
    fn new_session_reuses_empty_and_never_swallows_a_bad_directory() {
        let dir = std::env::temp_dir().join(format!("haoai-new-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("sessions")).unwrap();
        fs::write(
            dir.join("settings.json"),
            r#"{"baseUrl":"http://x/v1","model":"m","permissionMode":"ask","workspace":"G:/HaoAI"}"#,
        )
        .unwrap();
        let app = test_app(&dir);

        // 全新状态根 → 新建一条，档位必须回给前端（前端只拿 id 就会沿用旧档位显示）
        let (id1, body) = new_json(&app, "{}");
        assert!(id1.starts_with("pc") && id1.len() >= 10, "id 形状该是 pc+8 位十六进制：{id1}");
        assert!(body.contains("\"mode\":\"ask\""), "{body}");
        assert!(body.contains("\"reused\":false"), "{body}");

        // 键序照 Engine.persist —— 会话文件是两端共读的，多一个键少一个都会被下一个人看见
        let f = dir.join(format!("sessions/pc-{id1}.json"));
        let v: serde_json::Value = serde_json::from_str(&fs::read_to_string(&f).unwrap()).unwrap();
        let keys: Vec<&str> = v.as_object().unwrap().keys().map(|s| s.as_str()).collect();
        assert_eq!(
            keys,
            [
                "id", "title", "workspace", "mode", "persona", "role", "model", "toolsOff",
                "updated", "promptTokens", "completionTokens", "todos", "messages",
            ],
            "{keys:?}"
        );
        assert_eq!(v["title"], "新会话");
        assert_eq!(v["mode"], "ask", "新会话继承的是**全局默认**，不是上一条会话的档位");

        // 还一条没说过 → 复用同一个 id
        let (id2, body2) = new_json(&app, "{}");
        assert_eq!(id1, id2, "空的当前会话要复用，不然每点一次就多一个空文件");
        assert!(body2.contains("\"reused\":true"), "{body2}");

        // 写进一条消息 → 这次必须新建
        let mut v: serde_json::Value = serde_json::from_str(&fs::read_to_string(&f).unwrap()).unwrap();
        v["messages"] = serde_json::json!([{ "role": "user", "content": "说过话了" }]);
        fs::write(&f, v.to_string()).unwrap();
        let (id3, body3) = new_json(&app, "{}");
        assert_ne!(id1, id3, "说过话的会话不能再被当成空的复用");
        assert!(body3.contains("\"reused\":false"), "{body3}");

        // 指定目录打不开 → 明确报错，**不退回默认工作区**
        let (_, bad) = new_json(&app, r#"{"ws":"G:/definitely-missing-dir-x"}"#);
        assert!(bad.contains("\"ok\":false"), "{bad}");
        assert!(bad.contains("这个目录打不开"), "{bad}");

        // 角色卡/团队还没搬，但要**说出来**，不能静默建一条没有角色的会话
        let (_, no_preset) = new_json(&app, r#"{"preset":"someone"}"#);
        assert!(no_preset.contains("\"ok\":false") && no_preset.contains("/api/presets"), "{no_preset}");

        let _ = fs::remove_dir_all(&dir);
    }

    /// `/api/mode` 的要害是**界面显示与引擎档位不能分家**：点「计划」之后
    /// `/api/state.mode` 必须真的变成 plan，否则用户看到只读、跑的却是自动。
    #[test]
    fn mode_route_switches_the_live_mode_and_state_shows_it() {
        let dir = std::env::temp_dir().join(format!("haoai-mode-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("sessions")).unwrap();
        fs::write(
            dir.join("settings.json"),
            r#"{"baseUrl":"http://x/v1","model":"m","permissionMode":"ask","workspace":"G:/HaoAI"}"#,
        )
        .unwrap();
        let app = test_app(&dir);
        let (id, _) = new_json(&app, "{}");

        // 切到 plan：回应、活档位、state 三处都要一起变
        let r = mode_route(&app, r#"{"mode":"plan"}"#);
        assert_eq!(r, format!(r#"{{"ok":true,"mode":"plan"}}"#), "{r}");
        assert_eq!(app.mode_of(&id, "ask"), "plan");
        let (.., state) = state_body(&app, "");
        let v: serde_json::Value = serde_json::from_str(&state).unwrap();
        assert_eq!(v["mode"], "plan", "界面读的是 state.mode，它分家就是那个静默失效");

        // 垃圾值：回当前档位，不动（Kotlin 是 `m in listOf("plan","ask","auto")`）
        let r2 = mode_route(&app, r#"{"mode":"yolo"}"#);
        assert!(r2.contains("\"mode\":\"plan\""), "报的应该是当前档位：{r2}");
        assert_eq!(app.mode_of(&id, "ask"), "plan", "无效值不许把档位改成别的");

        // 切回 auto 也一样通
        assert!(mode_route(&app, r#"{"mode":"auto"}"#).contains("\"mode\":\"auto\""));
        let (.., state2) = state_body(&app, "");
        assert!(
            serde_json::from_str::<serde_json::Value>(&state2).unwrap()["mode"] == "auto",
            "state 要跟着变回来"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    /// 给会话路由用的一个干净状态根：一条会话 + 一条空会话。
    fn session_home(tag: &str) -> std::path::PathBuf {
        let root = std::env::temp_dir().join(format!("haoai-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("sessions")).unwrap();
        fs::write(
            root.join("sessions/pc-a.json"),
            r#"{"id":"a","title":"第一条","workspace":"G:/x","mode":"ask","updated":7,"messages":[]}"#,
        )
        .unwrap();
        fs::write(
            root.join("sessions/pc-b.json"),
            r#"{"id":"b","title":"第二条","workspace":"G:/x","mode":"ask","updated":5,"messages":[]}"#,
        )
        .unwrap();
        root
    }

    #[test]
    fn rename_reports_200_only_when_the_file_actually_changed() {
        let root = session_home("r-route");
        let app = test_app(&root);
        // 成功：200 + {"ok":true}，文件与 state 一起变
        let (code, body) = rename_session(&app, r#"{"id":"a","title":"改好的名字"}"#);
        assert_eq!(code, 200, "{body}");
        assert_eq!(body, r#"{"ok":true}"#);
        app.open_for_test("a");
        let (.., state) = state_body(&app, "a");
        assert!(state.contains(r#""title":"改好的名字""#), "{state}");
        // 全空白标题 → 404（不是"改成空"，也不是静默成功）
        let (code, body) = rename_session(&app, r#"{"id":"a","title":"   "}"#);
        assert_eq!(code, 404, "{body}");
        assert_eq!(body, r#"{"ok":false}"#);
        // 没有这条会话 → 404
        let (code, _) = rename_session(&app, r#"{"id":"nope","title":"x"}"#);
        assert_eq!(code, 404);
        let _ = fs::remove_dir_all(&root);
    }

    /// 数字形态的字段也要认：`Body.str` 取的是 `jsonPrimitive.content`，
    /// 前端把 index 当数字发时，只认字符串的取值会静默变成"什么都没删"。
    #[test]
    fn a_number_in_the_body_reads_as_its_literal_text() {
        assert_eq!(body_str(r#"{"index":5}"#, "index"), "5");
        assert_eq!(body_str(r#"{"on":true}"#, "on"), "true");
        assert_eq!(body_str(r#"{"x":null}"#, "x"), "");
        assert_eq!(body_str(r#"{"x":{"y":1}}"#, "x"), "");
        assert_eq!(body_str(r#"{"x":"真字符串"}"#, "x"), "真字符串");
    }

    #[test]
    fn delete_pinned_and_the_trash_routes_agree_on_every_response_shape() {
        let root = session_home("d-route");
        let app = test_app(&root);

        // 置顶：成功回 {"ok":true}，失败回的是**带 error 的 200**（Kotlin 就是这么分的）
        assert_eq!(pin_session(&app, r#"{"id":"a","pinned":"true"}"#), r#"{"ok":true}"#);
        assert_eq!(
            pin_session(&app, r#"{"id":"ghost","pinned":"1"}"#),
            r#"{"ok":false,"error":"没有这条会话"}"#
        );
        let listed = state::sessions_json(&app.store.list(50), "");
        assert!(listed.contains(r#""pinned":true"#), "置顶的必须排最前：{listed}");

        // 跑着的会话不许删：409 + 那句原话，文件必须还在原地
        assert!(app.try_begin("b"));
        let (code, body) = delete_session(&app, r#"{"id":"b"}"#);
        assert_eq!(code, 409, "{body}");
        assert_eq!(body, r#"{"ok":false,"error":"这个会话正在跑，先停止再删"}"#);
        assert!(root.join("sessions/pc-b.json").is_file(), "被拒的删除不能真的动过文件");
        app.end_run("b");

        // 停下来的那条：200 + 移进回收站，列表里不再出现
        // 先看一眼 b 再看 a —— 最近表就成了 [a, b]，删掉 a 之后"当前"该落到 b
        app.open_for_test("b");
        app.open_for_test("a");
        let (code, body) = delete_session(&app, r#"{"id":"a"}"#);
        assert_eq!(code, 200, "{body}");
        assert_eq!(body, r#"{"ok":true}"#);
        assert!(!root.join("sessions/pc-a.json").exists());
        let trash: Value = serde_json::from_str(&trash_body(&app)).unwrap();
        assert_eq!(trash.as_array().unwrap().len(), 1, "{trash}");
        let row = &trash[0];
        assert_eq!(row["id"], "a", "id 从文件内容读：{row}");
        assert_eq!(row["title"], "第一条");
        assert!(row["name"].as_str().unwrap().ends_with("-pc-a.json"), "{row}");
        assert!(row["bytes"].as_i64().unwrap() > 0);
        // 删掉的那条不能再驻留；"当前"要落到**次新的那条**上（不是变成没有当前）
        assert!(!app.resident.lock().unwrap().contains("a"), "删掉的会话还驻留着");
        assert_eq!(app.current_id(), "b", "currentId() = 最近表里第一个还驻留的");
        // 没有引擎那一条分支：工具清单与上下文构成都必须是空的
        // （`e?.toolInfos()` / `e?.contextBreakdown()` 在 Kotlin 那边整块不成立）
        let (.., ghost) = state_body(&app, "ghost");
        let g: Value = serde_json::from_str(&ghost).unwrap();
        assert_eq!(g["tools"].as_array().unwrap().len(), 0, "没有会话还报 32 把工具：{ghost}");
        assert_eq!(g["context"]["chars"], 0);
        assert_eq!(g["context"]["parts"].as_array().unwrap().len(), 0);
        assert_eq!(g["title"], "新会话", "没有引擎时标题回默认值，不是猜一个");

        // 放回原位 → 再彻底删掉
        let out = untrash(&app, &format!(r#"{{"name":"{}"}}"#, row["name"].as_str().unwrap()));
        assert!(out.contains(r#""ok":true"#) && out.contains(r#""id":"a""#), "{out}");
        assert!(root.join("sessions/pc-a.json").is_file());
        let gone = delete_session(&app, r#"{"id":"a"}"#);
        assert_eq!(gone.0, 200);
        let name: String = serde_json::from_str::<Value>(&trash_body(&app)).unwrap()[0]["name"]
            .as_str()
            .unwrap()
            .to_string();
        app.store.purge(&name);
        assert_eq!(trash_body(&app), "[]", "清空之后必须是空数组");
        let _ = fs::remove_dir_all(&root);
    }

}
