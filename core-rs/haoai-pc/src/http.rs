use std::fs;
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
    let method = lines.next().unwrap_or("GET").to_string();
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
        // 与 Kotlin 一样按 **method** 分流这一条：GET 是读，POST 是存
        "/api/settings" => {
            if method == "POST" {
                let (code, b) = save_settings(&app, &body);
                send(stream, code, "application/json; charset=utf-8", &b)
            } else {
                let b = settings_json(&app);
                send(stream, 200, "application/json; charset=utf-8", &b)
            }
        }
        "/api/models" => {
            let b = models_body(&app, &query_of(&query, "sid"));
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/model" => {
            let b = model_set(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
        }
        "/api/tool" => {
            let b = tool_toggle(&app, &body);
            send(stream, 200, "application/json; charset=utf-8", &b)
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
    // 驻留的那条用**它自己那份**设置（Kotlin 读的是 `e.settings`：会话文件的 model/toolsOff
    // 覆盖过全局的）；没驻留的用全局那份（`e` 是 null，`?:` 全部落到 settings.*）。
    let st = if resident { app.settings_of(&id) } else { app.global() };
    let view = View {
        settings: &st,
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
    let g = app.global();
    let ws = if want_ws.is_empty() { g.workspace_file() } else { want_ws.clone() };
    if !want_ws.is_empty() && !std::path::Path::new(&ws).is_dir() {
        return err_json(&format!("这个目录打不开：{ws}"));
    }
    let id = app.store.create(&ws, &g.permission_mode, &g);
    // 指定了目录那一支带 role 键、没指定那一支不带 —— 两边形状不一样是 Kotlin 原样，
    // 前端读的是 `d.role||''`，少一个键不报错，但要对齐就对齐到底
    created(app, &id, "新会话", &g.permission_mode, false, !want_ws.is_empty())
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
            .unwrap_or_else(|| app.global().permission_mode);
        app.mode_of(&sid, &file_mode)
    } else {
        app.global().permission_mode
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

/// 环境变量里的凭据：只有**非空白**才算设过（Kotlin 那边是 `takeIf { it.isNotBlank() }`）。
fn env_nonblank(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.trim().is_empty())
}

/// `GET /api/settings` —— 整份设置对象原样发给前端。
///
/// **密钥永远不进它**：只报 `hasKey`/`hasSearchKey` 两个布尔（v0.61 之前连 key 的前 6 位
/// 都发，那次一并收掉了）。键的顺序照 `settingsJson` 抄 —— 前端不按键序读，但像素剧本
/// 比的是文本，而且这份对象两端要能对照。
fn settings_json(app: &App) -> String {
    let st = app.global();
    let key = env_nonblank("HAOAI_API_KEY").or_else(|| {
        let p = app.store.home.join("apikey");
        if p.is_file() { fs::read_to_string(&p).ok().map(|t| t.trim().to_string()) } else { None }
    });
    let search_key = env_nonblank("HAOAI_SEARCH_KEY").unwrap_or_else(|| {
        let p = app.store.home.join("searchkey");
        p.is_file().then(|| fs::read_to_string(&p).ok().map(|t| t.trim().to_string())).flatten().unwrap_or_default()
    });
    let flags: Vec<String> = crate::flags::Flag::ALL
        .iter()
        .map(|f| {
            format!(
                "{{\"key\":{},\"title\":{},\"what\":{},\"on\":{}}}",
                state::quote(f.key()),
                state::quote(f.title()),
                state::quote(f.what()),
                crate::flags::enabled(*f, &st.flags)
            )
        })
        .collect();
    let ws = st.workspace_file();
    let rules: Vec<String> = app
        .policies_with(|p| p.rules(std::path::Path::new(&ws)))
        .iter()
        .map(|r| format!("{{\"text\":{}}}", state::quote(&r.render())))
        .collect();
    let off: Vec<String> = st.tools_off.iter().map(|t| state::quote(t)).collect();
    let mut s = String::new();
    s.push('{');
    s.push_str(&format!("\"provider\":{},", state::quote(&st.provider_name)));
    s.push_str(&format!("\"baseUrl\":{},", state::quote(&st.base_url)));
    s.push_str(&format!("\"model\":{},", state::quote(&st.model)));
    s.push_str(&format!("\"mode\":{},", state::quote(&st.permission_mode)));
    s.push_str(&format!("\"maxTokens\":{},", st.max_tokens));
    s.push_str(&format!("\"reasoningEffort\":{},", state::quote(&st.reasoning_effort)));
    s.push_str(&format!("\"searchProvider\":{},", state::quote(&st.search_provider)));
    s.push_str(&format!("\"fallback\":{},", state::quote(&st.fallback)));
    // 市场地址必须回得来：前端与像素剧本判"这个功能开了没"读的就是这份对象。
    // 第一次踩的时候这里没有这个键，于是 `settings.expertFeed` 永远 undefined ——
    // 就算 CLI 真写进去了，判据也还是红的，而症状看着像"市场整个坏了"。
    s.push_str(&format!("\"expertFeed\":{},", state::quote(&st.expert_feed)));
    s.push_str(&format!("\"hasSearchKey\":{},", !search_key.trim().is_empty()));
    // 上下文窗口必须回得去：抽屉里那一格原来永远是空的，而保存时 `num()` 把空读成 0 ——
    // 于是"打开设置再保存"就把窗口清零了
    s.push_str(&format!("\"contextChars\":{},", st.context_chars));
    s.push_str(&format!("\"workspace\":{},", state::quote(&ws)));
    s.push_str(&format!("\"toolsOff\":[{}],", off.join(",")));
    // 只报"有没有 key"，不报 key 本身：这份对象会整体发给浏览器
    s.push_str(&format!("\"hasKey\":{},", key.is_some()));
    s.push_str(&format!("\"flags\":[{}],\"rules\":[{}]}}", flags.join(","), rules.join(",")));
    s
}

/// `POST /api/settings` —— 逐键判"有没有给值"，给了才改，其余原样留着。
///
/// 三处口径是踩过坑写死的：`contextChars` **只认正数**（读成 0 会让界面上那圈占用
/// 永远 0%，比留空更骗人）；`reasoningEffort` 与 `fallback`、`expertFeed` **允许清空**
/// （空串是合法值，"关掉这一页"就靠它）；`mode` 只认 plan/ask/auto 三个词。
/// 密钥不在这里写：一把 key 只留 `/api/secrets` 一个入口。
fn save_settings(app: &App, body: &str) -> (u16, String) {
    let Ok(v) = serde_json::from_str::<serde_json::Value>(body) else {
        return (400, r#"{"ok":false}"#.to_string());
    };
    let mut n = app.global();
    let field = |k: &str| -> Option<String> {
        v.get(k).and_then(|x| match x {
            serde_json::Value::Null => None,
            serde_json::Value::String(s) => Some(s.clone()),
            serde_json::Value::Number(_) | serde_json::Value::Bool(_) => Some(x.to_string()),
            _ => None,
        })
    };
    if let Some(t) = field("model").filter(|t| !t.is_empty()) {
        n.model = t;
    }
    if let Some(t) = field("baseUrl").filter(|t| !t.is_empty()) {
        n.base_url = t;
    }
    if let Some(t) = field("maxTokens").and_then(|t| t.parse::<i64>().ok()) {
        n.max_tokens = t.max(0);
    }
    if let Some(t) = field("searchProvider").filter(|t| !t.is_empty()) {
        n.search_provider = t.trim().to_lowercase();
    }
    if let Some(t) = field("contextChars").and_then(|t| t.parse::<i64>().ok()) {
        if t > 0 {
            n.context_chars = t;
        }
    }
    if let Some(t) = field("reasoningEffort") {
        n.reasoning_effort = t.trim().to_lowercase();
    }
    if let Some(t) = field("fallback") {
        n.fallback = t.trim().to_string();
    }
    if let Some(t) = field("expertFeed") {
        n.expert_feed = t.trim().to_string();
    }
    if let Some(t) = field("mode").filter(|t| matches!(t.as_str(), "plan" | "ask" | "auto")) {
        n.permission_mode = t.clone();
        // 权限模式是全局设置，但要立刻反映到**每一个**活着的会话上，
        // 否则切回后台那条时它会继续用旧模式跑
        let resident: Vec<String> = app
            .resident
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .iter()
            .cloned()
            .collect();
        for sid in resident {
            app.set_mode(&sid, &t);
        }
    }
    if let Some(t) = field("workspace").filter(|t| !t.is_empty()) {
        n.workspace = t;
    }
    // 全局工具开关：**显式数组才动**，critical 写入即剔 ——
    // 不带这个键的其它保存路径（设置抽屉）完全不受影响
    if let Some(arr) = v.get("toolsOff").and_then(|x| x.as_array()) {
        n.tools_off = arr
            .iter()
            .filter_map(|x| x.as_str().map(String::from))
            .filter(|t| !crate::guard::is_critical(t))
            .collect();
    }
    if let Some(obj) = v.get("flags").and_then(|x| x.as_object()) {
        let mut merged = n.flags.clone();
        for (k, x) in obj {
            // Kotlin 是 `v.jsonPrimitive.content == "true"`：布尔 true 与字符串 "true" 都算开，
            // 数字 1 不算 —— 手机端往这个文件里写的可能是其中任意一种
            let on = crate::store::kstr(Some(x)).map(|t| t == "true").unwrap_or(false);
            match merged.iter_mut().find(|(mk, _)| mk == k) {
                Some(e) => e.1 = on,
                None => merged.push((k.clone(), on)),
            }
        }
        // `HaoFlag.compactOverrides`：只留**与默认值不同**的那几项，而且只认注册表里的 7 个键
        // —— 手改进去的一个陌生键不会被存下来，它谁也不代表。顺序照注册表。
        n.flags = crate::flags::Flag::ALL
            .iter()
            .filter_map(|f| {
                let on = merged.iter().find(|(k, _)| k == f.key()).map(|(_, v)| *v)?;
                if on == f.default_on() {
                    None
                } else {
                    Some((f.key().to_string(), on))
                }
            })
            .collect();
    }
    app.store.save_settings(&n);
    app.set_global(n.clone());
    // 全局设置变了，**每条活着的会话都要跟上**：引擎各自握着构造时那份设置
    // （模型、baseUrl、压缩阈值都在里面），不换的话界面上显示新模型、发请求用旧的。
    let live: Vec<String> = app.resident.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned().collect();
    for sid in live {
        // Kotlin 那边是 `useSettings(n)`：整份换成全局，**这条会话自己的 model/toolsOff 一起被抹掉**
        // （直到下一次 persist 才把新值写回文件）。跑着的那条不改档位。
        app.set_session_model(&sid, &n.model);
        app.set_session_tools_off(&sid, &n.tools_off);
        if !app.is_running(&sid) {
            app.set_mode(&sid, &n.permission_mode);
        }
    }
    app.publish("", "settings", &format!("{{\"mode\":{}}}", state::quote(&n.permission_mode)));
    (200, r#"{"ok":true}"#.to_string())
}

/// `GET /api/models?sid=` —— 列网关上的模型，给顶栏的模型切换器用。
fn models_body(app: &App, want: &str) -> String {
    let sid = if want.is_empty() { app.current_id() } else { want.to_string() };
    let resident = !sid.is_empty()
        && app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid);
    let rows: Vec<String> = if resident {
        let st = app.settings_of(&sid);
        let key_raw = fs::read_to_string(app.store.home.join("apikey")).unwrap_or_default();
        crate::provider::list_models(&st.base_url, &key_raw)
            .iter()
            .map(|m| format!("{{\"id\":{},\"current\":{}}}", state::quote(m), *m == st.model))
            .collect()
    } else {
        vec![]
    };
    format!("[{}]", rows.join(","))
}

/// `POST /api/model` {sid, model, scope} —— 换模型。
///
/// `scope` 缺省是 `session`：只改这一条引擎那份。以前全局只有一个模型，
/// 于是"这条会话拿本地小模型试个简单问题、别影响另外三条"做不到。
/// `scope=all` 才是全局路径：存盘 + 所有活着的会话一起跟上。
fn model_set(app: &App, body: &str) -> String {
    let m = ktrim_body(&body_str(body, "model")).to_string();
    if m.is_empty() {
        return err_json("模型名是空的");
    }
    let sid = pick_sid(app, body);
    let all = body_str(body, "scope") == "all";
    let scope = if all { "all" } else { "session" };
    if all {
        let mut n = app.global();
        n.model = m.clone();
        app.store.save_settings(&n);
        app.set_global(n.clone());
        let live: Vec<String> = app.resident.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned().collect();
        for id in live {
            app.set_session_model(&id, &n.model);
            app.set_session_tools_off(&id, &n.tools_off);
        }
    } else {
        let resident =
            !sid.is_empty() && app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid);
        if !resident {
            return err_json("没有这条会话，改不了模型");
        }
        app.set_session_model(&sid, &m);
    }
    app.publish(
        &sid,
        "model",
        &format!("{{\"model\":{},\"scope\":{}}}", state::quote(&m), state::quote(scope)),
    );
    if all {
        app.publish("", "settings", &format!("{{\"mode\":{}}}", state::quote(&app.global().permission_mode)));
    }
    format!(
        "{{\"ok\":true,\"model\":{},\"sid\":{},\"scope\":{}}}",
        state::quote(&m),
        state::quote(&sid),
        state::quote(scope)
    )
}

/**
 * `POST /api/tool` {sid,name,on} —— 这条会话开/关一把工具。
 *
 * 按会话而不是全局：让 agent 只做只读调研的那条，不该手里还握着 shell；
 * 而"全局关掉 shell"太狠，另开一条正经干活的任务就没法用了。
 * 与换模型不同：这一条 Kotlin 后面跟了一句 `persistNow()`，所以**会落盘**。
 */
fn tool_toggle(app: &App, body: &str) -> String {
    let sid = pick_sid(app, body);
    let name = body_str(body, "name");
    let on = {
        let o = body_str(body, "on");
        o == "1" || o == "true"
    };
    let resident =
        !sid.is_empty() && app.resident.lock().unwrap_or_else(|p| p.into_inner()).contains(&sid);
    if !resident || name.is_empty() {
        return err_json("没有这条会话或工具名是空的");
    }
    let mut off: Vec<String> = app.settings_of(&sid).tools_off;
    if on {
        off.retain(|t| t != &name);
    } else if !off.iter().any(|t| t == &name) {
        off.push(name.clone());
    }
    app.set_session_tools_off(&sid, &off);
    // 与 Kotlin 一样立刻落一次盘（`e.persistNow()`）：写的是**引擎当下那份** model 与 toolsOff
    engine::persist_now(app, &sid);
    let arr: Vec<String> = off.iter().map(|t| state::quote(t)).collect();
    app.publish(&sid, "tools", &format!("{{\"sid\":{}}}", state::quote(&sid)));
    format!("{{\"ok\":true,\"off\":{},\"toolsOff\":[{}]}}", !on, arr.join(","))
}

/// Kotlin 的 `String.trim()`（同一个口径，别用 Rust 的）
fn ktrim_body(s: &str) -> &str {
    crate::store::ktrim(s)
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


    /// GET /api/settings 的形状：整份设置原样发回浏览器，**但密钥永远不在里面**。
    #[test]
    fn settings_get_sends_the_whole_object_but_never_the_key() {
        let root = session_home("s-get");
        fs::write(
            root.join("settings.json"),
            r#"{"providerName":"本地llama","baseUrl":"http://127.0.0.1:8131/v1","model":"m1","workspace":"G:/Rust/verify/ws","permissionMode":"ask","maxTurns":60,"temperature":0.3,"maxTokens":4096,"reasoningEffort":"","fallback":"","searchProvider":"auto","embedUrl":"","expertFeed":"","contextChars":128000,"storedCap":16000,"reqCap":4000,"compactTriggerChars":60000,"compactKeepTail":14,"toolsOff":"[]","flags":{"browser_control":true}}"#,
        )
        .unwrap();
        fs::write(root.join("apikey"), "sk-abcdefghijklmnopqrstuvwxyz").unwrap();
        let app = test_app(&root);
        let body = settings_json(&app);
        let v: Value = serde_json::from_str(&body).unwrap();
        assert_eq!(v["provider"], "本地llama", "键名是 provider 而不是 providerName：{v}");
        assert_eq!(v["model"], "m1");
        assert_eq!(v["maxTokens"], 4096);
        assert_eq!(v["contextChars"], 128000, "这一格不发回去，保存一次就把窗口清零了");
        assert_eq!(v["hasKey"], true);
        assert_eq!(v["hasSearchKey"], false);
        assert!(!body.contains("sk-abcd"), "明文 key 漏进 /api/settings：{body}");
        assert!(!body.contains("keyHint"), "连前 6 位都不许发：{body}");
        // 反斜杠归一 + canonical：workspace 报的是 File.absolutePath 那一形
        assert_eq!(v["workspace"], r"G:\Rust\verify\ws", "{v}");
        assert!(!v["workspace"].as_str().unwrap().starts_with(r"\\?\"), "canonicalize 的前缀没剥掉：{v}");
        assert_eq!(v["flags"].as_array().unwrap().len(), 7, "HaoFlag 一共 7 条：{v}");
        let bc = v["flags"].as_array().unwrap().iter().find(|f| f["key"] == "browser_control").unwrap();
        assert_eq!(bc["on"], true, "文件里写了布尔 true 要认：{bc}");
        assert_eq!(bc["title"], "浏览器控制（CDP）");
        assert!(bc["what"].as_str().unwrap().contains("默认关"), "{bc}");
        let ow = v["flags"].as_array().unwrap().iter().find(|f| f["key"] == "outside_write").unwrap();
        assert_eq!(ow["on"], false, "没写的键回落默认（往外写默认关）");
        let _ = fs::remove_dir_all(&root);
    }

    /// POST 是"逐键判给没给"，不是整份替换；落盘形状（含那个手写 .indent()）要两端一样。
    #[test]
    fn settings_post_changes_only_the_keys_given_and_writes_the_jvm_shape() {
        let root = session_home("s-post");
        fs::write(
            root.join("settings.json"),
            r#"{"providerName":"p","baseUrl":"http://x/v1","model":"m1","workspace":"","permissionMode":"ask","maxTurns":60,"temperature":0.3,"maxTokens":4096,"reasoningEffort":"","fallback":"","searchProvider":"auto","embedUrl":"","expertFeed":"","contextChars":128000,"storedCap":16000,"reqCap":4000,"compactTriggerChars":60000,"compactKeepTail":14,"toolsOff":"[]","flags":{}}"#,
        )
        .unwrap();
        let app = test_app(&root);
        let (code, body) = save_settings(
            &app,
            r#"{"model":"m2","contextChars":0,"maxTokens":-5,"expertFeed":"","reasoningEffort":"HIGH"}"#,
        );
        assert_eq!(code, 200, "{body}");
        let st = app.global();
        assert_eq!(st.model, "m2");
        assert_eq!(st.context_chars, 128_000, "窗口只认正数：读成 0 那圈占用就永远 0%");
        assert_eq!(st.max_tokens, 0, "maxTokens 允许清 0（0 = 不发这个字段，交给网关）");
        assert_eq!(st.expert_feed, "", "空串是合法值：清空 = 关掉这一页");
        assert_eq!(st.reasoning_effort, "high", "存的是小写");

        let on_disk = fs::read_to_string(root.join("settings.json")).unwrap();
        // 数字后面那个键边界**不会**换行：.indent() 只认 `","`、`{"`、`"}` 三种字面
        assert!(on_disk.contains("\"maxTurns\":60,\"temperature\":0.3,\"maxTokens\":0"), "{on_disk}");
        assert!(on_disk.starts_with("{\n\"providerName\":\"p\","), "{on_disk}");
        // toolsOff 是**字符串**形态：Kotlin 那边 put 的是 joinToString 的结果，
        // 于是它写进去就读不回来（全局工具开关活不过重启）—— 这是那边的既有行为，照抄
        assert!(on_disk.contains(r#""toolsOff":"[]""#), "{on_disk}");

        // 显式数组才动全局工具开关，而且 critical 写入即剔
        save_settings(&app, r#"{"toolsOff":["shell","grep","read"]}"#);
        assert_eq!(app.global().tools_off, vec!["shell".to_string()], "critical 那五把关不掉");
        // 不带这个键的保存路径完全不受影响
        save_settings(&app, r#"{"model":"m3"}"#);
        assert_eq!(app.global().tools_off, vec!["shell".to_string()]);

        // flags：与默认相同的项不写盘（compactOverrides），而且只认注册表里那 7 个键
        save_settings(&app, r#"{"flags":{"outside_write":false,"plan_mode":false,"made_up":true}}"#);
        assert_eq!(
            app.global().flags,
            vec![("plan_mode".to_string(), false)],
            "outside_write 关着就是默认值，不留痕；陌生键不收"
        );
        // 解析不出来的体 → 400
        let (code, _) = save_settings(&app, "not json");
        assert_eq!(code, 400);
        let _ = fs::remove_dir_all(&root);
    }

    /// 全局模式一改，**每条活着的会话**都要跟上（否则切回后台那条还按旧模式跑）。
    #[test]
    fn a_global_mode_save_reaches_every_live_session() {
        let root = session_home("s-mode");
        let app = test_app(&root);
        app.open_for_test("b");
        app.open_for_test("a");
        assert!(app.try_begin("b"), "让 b 处在跑着的状态");
        save_settings(&app, r#"{"mode":"plan"}"#);
        assert_eq!(app.global().permission_mode, "plan");
        assert_eq!(app.mode_of("a", "ask"), "plan", "没在跑的那条要跟上");
        // 模式那一段的两个 forEach 都会改档位；紧接着的"跑着的不改"补一遍，所以跑着的那条回到新默认值
        assert_eq!(app.mode_of("b", "ask"), "plan");
        app.end_run("b");
        let _ = fs::remove_dir_all(&root);
    }

    /// 按会话换模型只改这条；scope=all 才存盘并让所有活着的会话跟上。
    #[test]
    fn switching_the_model_per_session_leaves_the_global_one_alone() {
        let root = session_home("m-route");
        fs::write(
            root.join("settings.json"),
            r#"{"providerName":"p","baseUrl":"http://x/v1","model":"全局模型","workspace":"G:/x","permissionMode":"ask","maxTokens":4096,"contextChars":128000,"compactTriggerChars":60000,"toolsOff":[],"flags":{}}"#,
        )
        .unwrap();
        let app = test_app(&root);
        assert!(model_set(&app, r#"{"model":"   "}"#).contains("模型名是空的"));
        let r = model_set(&app, r#"{"model":"只这条","sid":"nope-not-here"}"#);
        assert!(r.contains("没有这条会话，改不了模型"), "{r}");
        assert_eq!(app.global().model, "全局模型");

        app.open_for_test("a");
        let r = model_set(&app, r#"{"model":"本地 7B","sid":"a"}"#);
        assert!(r.contains(r#""scope":"session""#) && r.contains("本地 7B"), "{r}");
        assert_eq!(app.settings_of("a").model, "本地 7B", "这条会话自己要用的那份");
        assert_eq!(app.global().model, "全局模型", "全局没动");
        assert_eq!(app.settings_of("b").model, "全局模型", "别条会话不受影响");
        let (.., st) = state_body(&app, "a");
        let v: Value = serde_json::from_str(&st).unwrap();
        assert_eq!(v["model"], "本地 7B", "顶栏那个标签报的是这条会话自己的模型：{v}");
        // 按会话改的那一条**不落盘**（Kotlin 后面没有 persistNow）
        let on_disk: Value =
            serde_json::from_str(&fs::read_to_string(root.join("sessions/pc-a.json")).unwrap()).unwrap();
        assert_ne!(on_disk["model"].as_str().unwrap_or(""), "本地 7B", "这条不该已经写进文件");

        // scope=all：存盘 + 活着的每条都整份换成全局那份
        let r = model_set(&app, r#"{"model":"云端大模型","scope":"all"}"#);
        assert!(r.contains(r#""scope":"all""#), "{r}");
        assert_eq!(app.global().model, "云端大模型");
        assert!(fs::read_to_string(root.join("settings.json")).unwrap().contains("云端大模型"));
        assert_eq!(app.settings_of("a").model, "云端大模型", "全局一改，活着的会话都要跟上");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_tool_toggle_is_per_session_and_persists() {
        let root = session_home("t-route");
        fs::write(
            root.join("settings.json"),
            r#"{"baseUrl":"http://x/v1","model":"m","workspace":"G:/x","permissionMode":"ask","toolsOff":[],"flags":{}}"#,
        )
        .unwrap();
        let app = test_app(&root);
        app.open_for_test("a");
        app.open_for_test("b");
        let r = tool_toggle(&app, r#"{"sid":"a","name":"shell","on":"0"}"#);
        assert!(r.contains(r#""off":true"#) && r.contains("\"shell\""), "{r}");
        assert_eq!(app.tools_off_of("a"), vec!["shell".to_string()]);
        assert!(app.tools_off_of("b").is_empty(), "别条会话不受影响");
        // 这一条**会落盘**（Kotlin 后面跟了 persistNow）
        let on_disk: Value =
            serde_json::from_str(&fs::read_to_string(root.join("sessions/pc-a.json")).unwrap()).unwrap();
        assert_eq!(on_disk["toolsOff"], serde_json::json!(["shell"]), "{on_disk}");
        tool_toggle(&app, r#"{"sid":"a","name":"shell","on":"1"}"#);
        assert!(app.tools_off_of("a").is_empty());
        // critical 那五把：写进表里也不算关（`effectiveToolsOff` 两层都剔）
        tool_toggle(&app, r#"{"sid":"a","name":"read","on":"0"}"#);
        assert!(app.tools_off_of("a").is_empty(), "read 是 critical，关掉等于砍掉引擎");
        assert!(tool_toggle(&app, r#"{"sid":"a","name":"","on":"0"}"#).contains("没有这条会话或工具名是空的"));
        assert!(tool_toggle(&app, r#"{"sid":"ghost","name":"shell","on":"0"}"#).contains("没有这条会话或工具名是空的"));
        let _ = fs::remove_dir_all(&root);
    }

    /// `/api/models` 走的是网关：两种形状都要认，"使用中"按**这条会话**的模型标。
    #[test]
    fn models_route_reads_both_gateway_shapes_and_marks_the_live_model() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            for s in l.incoming() {
                let mut s = s.unwrap();
                let mut buf = [0u8; 512];
                let n = s.read(&mut buf).unwrap_or(0);
                let head = String::from_utf8_lossy(&buf[..n]).to_string();
                let body = if head.contains("/llama/") {
                    r#"{"models":[{"name":"本地 7B"},{"name":"本地 13B"}]}"#.to_string()
                } else {
                    // 重复 id 要去掉：网关有时会回两条同名的
                    r#"{"data":[{"id":"云端 A"},{"id":"云端 B"},{"id":"云端 A"}]}"#.to_string()
                };
                let _ = s.write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
                        body.len(),
                        body
                    )
                    .as_bytes(),
                );
            }
        });
        let root = session_home("md-route");
        fs::write(
            root.join("settings.json"),
            format!(r#"{{"baseUrl":"http://127.0.0.1:{port}/v1","model":"云端 B","workspace":"G:/x","permissionMode":"ask","toolsOff":[],"flags":{{}}}}"#),
        )
        .unwrap();
        fs::write(root.join("apikey"), "sk-ascii").unwrap();
        let app = test_app(&root);
        // 没驻留的会话压根没有引擎可问 → 空表，不是"猜一个"
        assert_eq!(models_body(&app, "a"), "[]");
        app.open_for_test("a");
        let v: Value = serde_json::from_str(&models_body(&app, "a")).unwrap();
        assert_eq!(v.as_array().unwrap().len(), 2, "重复的 id 要去重：{v}");
        assert_eq!(v[0]["id"], "云端 A");
        assert_eq!(v[1]["current"], true, "使用中要按**这条会话**的模型标：{v}");
        // 按会话换模型之后，打勾的那一行跟着挪
        model_set(&app, r#"{"model":"云端 A","sid":"a"}"#);
        let v: Value = serde_json::from_str(&models_body(&app, "a")).unwrap();
        assert_eq!(v[0]["current"], true, "{v}");
        assert_eq!(v[1]["current"], false, "{v}");
        // llama-server 那种形状
        fs::write(
            root.join("settings.json"),
            format!(r#"{{"baseUrl":"http://127.0.0.1:{port}/llama/v1","model":"本地 13B","workspace":"G:/x","permissionMode":"ask","toolsOff":[],"flags":{{}}}}"#),
        )
        .unwrap();
        app.set_global(app.store.settings());
        let v: Value = serde_json::from_str(&models_body(&app, "a")).unwrap();
        assert_eq!(v[0]["id"], "本地 7B", "name 那个键也要认：{v}");
        let _ = fs::remove_dir_all(&root);
        drop(handle);
    }
}
