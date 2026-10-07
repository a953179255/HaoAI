//! 回合循环：`/api/task` → 多轮"模型要调工具 → 执行 → 回灌"直到它给出最终回答。
//! 移植自 `Engine` 跑回合那一段的**外部行为**（事件序列、历史结构、会话文件格式）。
//!
//! M2d-1 装了只读那一档工具（read/glob/grep/todo）。**没装的工具不是"未知工具"**：
//! 回一条 tool 结果说"尚未移植"，循环继续 —— 模型因此有机会换个能用的方案，
//! 而不是整轮崩掉。同理，被关掉的、计划模式下的、名字不认识的，各自回各自的措辞，
//! 全部照抄 Kotlin 的原文。
//!
//! 一条硬约束贯穿全文件：**每个 tool_call_id 都必须恰好有一条 tool 回复**。
//! 少一条，下一次请求就被网关判 400（OpenAI 兼容协议的硬要求）。
use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::thread;
use std::time::Instant;

use serde_json::{Map, Value};

use crate::prompt;
use crate::provider;
use crate::state::quote;
use crate::store::Msg;
use crate::tools_impl::{self, Ctx, Outcome};
use crate::utf16::utf16_take;
use crate::App;

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// 受理一次任务。返回 Err 是同步拒绝（对应 Kotlin 的 409）。
pub fn begin(app: Arc<App>, sid: String, text: String) -> Result<(), String> {
    if !app.try_begin(&sid) {
        return Err("这条会话正在跑一个任务，等它结束再说".to_string());
    }
    thread::spawn(move || {
        run(app, sid, text);
    });
    Ok(())
}

fn run(app: Arc<App>, sid: String, text: String) {
    app.publish(&sid, "user", &format!("{{\"t\":{},\"imgs\":[],\"media\":[]}}", quote(&text)));
    app.publish(&sid, "run", r#"{"running":true}"#);
    if let Err(e) = turn(&app, &sid, &text) {
        app.publish(&sid, "err", &quote(&e));
    }
    app.publish(&sid, "run", r#"{"running":false}"#);
    app.publish(&sid, "sessions", "{}");
    app.end_run(&sid);
}

/// 一整个回合。**测试直接同步调它**：异步版（`begin`）里"停止旗子立在第几毫秒"没法确定，
/// 而这条恰恰是最不能靠运气的不变量（每个 tool_call_id 恰好一条回复）。
pub(crate) fn turn(app: &App, sid: &str, text: &str) -> Result<(), String> {
    let sf = app.store.restore(sid).ok_or_else(|| format!("没有这条会话：{sid}"))?;
    let workspace = Path::new(&sf.meta.workspace).to_path_buf();
    // 档位从会话文件读一次、整轮都用它（Kotlin 看的是 session.mode，也不是每轮重读磁盘）。
    // 走 `mode_of` 是因为"本任务都允许"会在回合中途把这条会话切成 auto ——
    // 下一轮就该真的不再挡它，否则那颗按钮等于没按。
    let mode = app.mode_of(sid, &sf.meta.mode);
    let plan = mode == "plan";
    let run_id = format!("r{}", now_ms());
    let st0 = app.settings_of(sid);
    let ctx = Ctx::for_run(
        &app.store.home,
        workspace,
        // 这条会话该用哪份设置（全局 + 会话文件覆盖 + 按会话的活覆盖）
        st0.flags.clone(),
        &mode,
        sid,
        &run_id,
    );
    let pctx = prompt::ctx_for(&app.store, &sf.meta.workspace, &st0.model, &mode, &[]);
    let sys = prompt::system(&pctx);

    let mut hist = app.history(sid);
    if hist.is_empty() {
        hist = sf.msgs.clone();
    }
    /*
     * 自动取名：标题还是"新会话"时，用这一句的前 24 个 UTF-16 单元当标题。
     * 只在"还是默认名"时改一次，所以第二条消息不会把标题又换回去。
     * `trim` 是 Java 口径（不换行空格不算空白），`take` 是 UTF-16 口径 ——
     * 两端算出同一个标题，侧栏才不会一条叫"新会话"一条叫别的。
     */
    let cur_title = app.title_of(sid).unwrap_or_else(|| sf.meta.title.clone());
    app.set_title(sid, &cur_title);
    let titled = cur_title == "新会话";
    if titled {
        let t = crate::store::ktrim(text).replace('\n', " ");
        let t: String = crate::utf16::utf16_take(&t, 24).to_string();
        let t = if t.is_empty() || t.chars().all(crate::store::kotlin_ws) { "新会话".to_string() } else { t };
        app.set_title(sid, &t);
    }
    hist.push(Msg { role: "user".into(), content: text.to_string(), ..Default::default() });
    app.set_history(sid, hist.clone());
    // 头行：这一轮动了哪些文件要往这本账上记，而"回到这次任务之前"得先知道任务是什么。
    // `at` 是这句在历史里的下标 —— 按时间猜会退错：定时任务、手机派活都往同一条会话里追加消息。
    crate::checkpoints::begin(&app.store.home, sid, &run_id, text, hist.len() as i64 - 1);
    /*
     * 一开口就先落一次盘（`Engine.submit` 的原话）：之前只在回合结束时 persist，
     * 于是"正在跑的任务"在会话列表里根本不存在；顺带把手机端"落库止血"的规矩接上 ——
     * 进程被杀 / 断电时，至少用户问了什么还在。
     */
    persist(app, sid, &hist, &ctx);
    // 标题一落地就要说出去：侧栏与顶栏靠它区分并行的几条会话，
    // 等回合结束才刷新的话，一条跑十分钟的任务十分钟都还叫"新会话"。
    if titled {
        app.publish(sid, "title", &format!("{{\"title\":{}}}", quote(&app.title_of(sid).unwrap_or_default())));
    }

    let key_raw = fs::read_to_string(app.store.home.join("apikey")).unwrap_or_default();
    let max_turns = st0.max_turns.max(1) as usize;
    let mut last_text = String::new();
    let mut turn_no = 0usize;

    while turn_no < max_turns {
        if app.stopped(sid) {
            // 中断要**成为数据**，不能只是"停下来"：得留一条模型下一轮看得见的记录，
            // 否则它以为刚才那件事做完了，接着往下编。
            hist.push(Msg {
                role: "user".into(),
                content: "（用户在这一轮中途按了停止。之前请求的工具调用没有全部执行完，继续之前先确认现状，别假设计划已经跑完。）".into(),
                ..Default::default()
            });
            app.set_history(sid, hist.clone());
            app.publish(sid, "notice", &quote("已按你的要求中断。"));
            break;
        }
        turn_no += 1;
        // **每一轮**重取一次：中途改了全局设置或按会话换了模型，下一轮的请求就要跟上
        // （Kotlin 那边读的是 `engine.settings`，`useSettings` 一调它就变）
        let st = app.settings_of(sid);
        let body = provider::request_body(&st, &sys, &hist, plan);
        let mut on_delta = |s: &str| app.publish(sid, "delta", &quote(s));
        let mut on_reason = |s: &str| app.publish(sid, "reason", &quote(s));
        let started = Instant::now();

        let t = match provider::chat(&st, &key_raw, &body, &mut on_delta, &mut on_reason)
        {
            Ok(t) => t,
            Err(e) => return Err(format!("模型调用失败：{e}")),
        };
        let ms = started.elapsed().as_millis() as i64;

        app.add_usage(sid, t.prompt_tokens, t.completion_tokens);
        let (pt_all, ct_all) = app.usage_of(sid);
        app.publish(
            sid,
            "usage",
            &format!("{{\"prompt\":{pt_all},\"completion\":{ct_all},\"turns\":{turn_no}}}"),
        );

        if t.finish.eq_ignore_ascii_case("length") {
            app.publish(
                sid,
                "notice",
                &quote(&format!(
                    "这一轮说到一半被 max_tokens={} 截断了，下面这段可能不完整。要放宽就在设置里改 maxTokens（0 = 交给网关），或把这一步拆小一点。",
                    st.max_tokens
                )),
            );
        }
        app.publish(
            sid,
            "stats",
            &format!(
                "{{\"pt\":{},\"ct\":{},\"ms\":{ms},\"turns\":{turn_no}}}",
                t.prompt_tokens, t.completion_tokens
            ),
        );

        if t.tool_calls.is_empty() {
            last_text = t.text.clone();
            hist.push(Msg {
                role: "assistant".into(),
                content: t.text.clone(),
                reasoning: t.reasoning.clone(),
                pt: t.prompt_tokens,
                ct: t.completion_tokens,
                ms,
                ..Default::default()
            });
            app.set_history(sid, hist.clone());
            persist(app, sid, &hist, &ctx);
            app.publish(sid, "answer", &quote(&last_text));
            return Ok(());
        }

        if !t.text.trim().is_empty() {
            last_text = t.text.clone();
        }
        hist.push(Msg {
            role: "assistant".into(),
            content: t.text.clone(),
            reasoning: t.reasoning.clone(),
            calls: t.tool_calls.clone(),
            pt: t.prompt_tokens,
            ct: t.completion_tokens,
            ms,
            ..Default::default()
        });
        app.set_history(sid, hist.clone());
        // 每轮留一次现场：被打断时"跑到第几轮"才是量出来的
        persist(app, sid, &hist, &ctx);

        for (id, name, raw_args) in &t.tool_calls {
            if app.stopped(sid) {
                // 注意是 continue 不是 break：**每个 tool_call_id 都必须恰好有一条 tool 回复**，
                // 少一条下一次请求就被网关判 400。上屏的那句短的是"已中断，未执行"，
                // 进历史的是长的那句 —— 模型要知道自己是被谁在哪一步打断的。
                let why = "（用户在执行到这一步之前按了停止，这个调用没有执行。）".to_string();
                hist.push(Msg {
                    role: "tool".into(),
                    content: why.clone(),
                    call_id: id.clone(),
                    name: name.clone(),
                    ..Default::default()
                });
                app.set_history(sid, hist.clone());
                app.publish(sid, "tool", &tool_end_frame(id, name, false, "已中断，未执行", "generic", "", ""));
                continue;
            }
            // 闸口在 ToolStart **之前**（照 Kotlin 的顺序）：被拒的调用没真的动过，
            // 就不该在界面上先转一张"正在执行"的圈再变红。
            let out = match refuse(app, sid, name, plan, &ctx.flags) {
                Some(why) => Outcome { text: why, ok: false, card: "generic", diff: String::new() },
                None => {
                    app.publish(sid, "tool", &tool_start(id, name, raw_args));
                    exec(app, name, raw_args, &ctx)
                }
            };
            // 审批结论挂到紧接着落的那条工具消息上：只随 SSE 流一次的话，
            // 刷新之后卡没了，"这文件是用户点头写的还是自动写的"就查不出来。
            let note = app.take_note(sid);
            let text = out.text.clone();
            // 每个 tool_call_id 恰好一条回复，缺一条下一次请求就被网关判 400
            hist.push(Msg {
                role: "tool".into(),
                content: text.clone(),
                call_id: id.clone(),
                name: name.clone(),
                note: note.clone(),
                diff: out.diff.clone(),
                ..Default::default()
            });
            app.set_history(sid, hist.clone());
            app.publish(sid, "tool", &tool_end_frame(id, name, out.ok, &text, out.card, &note, &out.diff));
        }
        // 带着 tool 回复进下一轮
    }

    if turn_no >= max_turns {
        app.publish(
            sid,
            "notice",
            &quote(&format!("已达单轮工具调用上限 {max_turns}，先收尾。")),
        );
    }
    persist(app, sid, &hist, &ctx);
    app.publish(sid, "answer", &quote(&last_text));
    Ok(())
}

fn tool_start(id: &str, name: &str, raw_args: &str) -> String {
    format!(
        "{{\"id\":{},\"name\":{},\"brief\":{},\"state\":\"run\",\"subject\":{}}}",
        quote(id),
        quote(name),
        quote(&brief(raw_args)),
        quote(&subject_of(raw_args))
    )
}

/// `Ev.ToolEnd` → SSE 帧。键序照 `Server.forward`，且 **card 是渲染意图**
/// （generic|terminal|diff），不是工具名 —— 界面按 `d.card=='diff'` 决定要不要挂 diff 统计。
/// `note` 是用户对这一步的审批结论，画在卡头。
fn tool_end_frame(id: &str, name: &str, ok: bool, out: &str, card: &str, note: &str, diff: &str) -> String {
    format!(
        "{{\"id\":{},\"name\":{},\"ok\":{},\"card\":{},\"out\":{},\"diff\":{},\"note\":{},\
         \"sub\":\"\",\"media\":[],\"cites\":[]}}",
        quote(id),
        quote(name),
        ok,
        quote(card),
        quote(out),
        quote(diff),
        quote(note)
    )
}

/// 执行侧的可见性闸口，顺序与 `Engine` 里 `Ev.ToolStart` 之前那四段一致：
/// 未知 → 实验开关 → 计划模式 → 这条会话的开关。谁先拒就用谁的措辞。
/// 返回 Some(理由) 表示这一步**不做**，理由同时是回给模型的 tool 内容。
fn refuse(app: &App, sid: &str, name: &str, plan: bool, flags: &[(String, bool)]) -> Option<String> {
    if !crate::tools::TOOLS.iter().any(|t| t.name == name) {
        let avail = crate::tools::TOOLS.iter().map(|t| t.name).collect::<Vec<_>>().join(",");
        return Some(format!("未知工具：{name}。可用的是 {avail}"));
    }
    // 实验特性开关：**关着就等于这把工具没注册**，回"未知工具"会把人引偏，
    // 所以要指出去哪儿开（这是用户自己能在设置里拨的那一格）。
    if !crate::guard::visible(name, flags) {
        let key = crate::guard::flag_for(name).map(|f| f.key()).unwrap_or("");
        return Some(format!(
            "工具 {name} 没启用（实验特性 {key} 是关的）。要用户执行 haoai flags on {key} 才行；现在换个能用的方案。"
        ));
    }
    if plan && !tool_is_read_only(name) {
        return Some(format!(
            "计划模式是只读的，{name} 不能用。把要做的事写进计划，或用 ask_user 确认切档。"
        ));
    }
    if app.tools_off_of(sid).iter().any(|o| o == name) {
        return Some(format!("工具 {name} 在这条会话里被关掉了：右栏「工具」页签可以重新打开。"));
    }
    None
}

/// 过闸之后才轮到真执行。
fn exec(app: &App, name: &str, raw_args: &str, ctx: &Ctx) -> Outcome {
    let args: Value = serde_json::from_str(raw_args).unwrap_or(Value::Object(Map::new()));
    match tools_impl::dispatch(app, ctx, name, &args) {
        Some(o) => o,
        None => Outcome {
            text: format!(
                "工具 {name} 尚未移植到 Rust 引擎（M2e 目前只装了 read/glob/grep/todo/write/edit）。请换个能用的方案，或改用 Kotlin 引擎跑这类操作。"
            ),
            ok: false,
            card: "generic",
            diff: String::new(),
        },
    }
}

/// 与**可见性过滤同一把尺子**（见 `tools::read_only`）：判据写两遍迟早分叉，
/// 分叉的症状是"模型看得见却调不动"或"看不见却调得动"。
fn tool_is_read_only(name: &str) -> bool {
    crate::tools::TOOLS
        .iter()
        .find(|t| t.name == name)
        .map(crate::tools::read_only)
        .unwrap_or(false)
}

/// `Engine.brief(args)`：**不是**按工具名查键，而是按 `command → path → url → pattern → question`
/// 的顺序取第一个**存在的**参数，取 180 个 UTF-16 单元；一个都没有就用整份参数的字符串形态取 140。
///
/// （这里原来写成"按工具名查一个键 + 40 字 + 前缀 `名字 · `"，是我在 M2d-1 自己编的形状：
/// 界面上那张卡的副标题两端不一样长、还多带了个前缀。工具名本来就是卡片标题的一部分，
/// 副标题要放的是**这一刀动的是哪个对象**。）
fn brief(args_raw: &str) -> String {
    let v: Value = serde_json::from_str(args_raw).unwrap_or(Value::Null);
    for k in ["command", "path", "url", "pattern", "question"] {
        match v.get(k) {
            None | Some(Value::Null) => continue,
            // 数字/布尔在 Kotlin 那边 `jsonPrimitive.contentOrNull` 拿得到字符串，这里同口径
            Some(Value::String(s)) => return utf16_take(s, 180).to_string(),
            Some(Value::Number(n)) => return n.to_string(),
            Some(Value::Bool(b)) => return b.to_string(),
            /*
             * 数组/对象：Kotlin 的 `jsonPrimitive` 在这里会抛 —— 那是"一行卡片副标题"
             * 把整个回合打崩的抛法。不学它，退到后面那个整串兜底（与它 args 为 null 时
             * 走的是同一条路），并在测试里把这个差异钉住。
             */
            Some(_) => continue,
        }
    }
    utf16_take(&compact_args(&v), 140).to_string()
}

/// kotlinx 的 `JsonObject.toString()` 形态：紧凑、键按声明序、字符串带引号。
fn compact_args(v: &Value) -> String {
    match v {
        Value::Object(o) => {
            let inner = o
                .iter()
                .map(|(k, x)| format!("\"{k}\":{}", compact_args(x)))
                .collect::<Vec<_>>()
                .join(",");
            format!("{{{inner}}}")
        }
        Value::Array(a) => {
            format!("[{}]", a.iter().map(compact_args).collect::<Vec<_>>().join(","))
        }
        Value::String(s) => quote(s),
        Value::Null => "null".to_string(),
        other => other.to_string(),
    }
}

/// `Engine.subjectOf`：`path → file → url → command` 里第一个**非空白**的原始值。
/// 它是"回滚这次修改"和"退回上一版"要找的对象，所以空白不算数（`"path": ""` 要往下找）。
fn subject_of(args_raw: &str) -> String {
    let v: Value = serde_json::from_str(args_raw).unwrap_or(Value::Null);
    for k in ["path", "file", "url", "command"] {
        if let Some(s) = v.get(k).and_then(|x| x.as_str()) {
            if !s.trim().is_empty() {
                return s.to_string();
            }
        }
    }
    String::new()
}

/// 读回原文件、只改自己负责的键、再原子写回。保留未知键与键序（`preserve_order`）。
fn persist(app: &App, sid: &str, hist: &[Msg], ctx: &Ctx) {
    let path = app.store.home.join("sessions").join(format!("pc-{sid}.json"));
    let raw = fs::read_to_string(&path).unwrap_or_default();
    let mut v: Value = serde_json::from_str(&raw).unwrap_or_else(|_| Value::Object(Map::new()));
    let (pt, ct) = app.usage_of(sid);
    let st = app.settings_of(sid);
    if let Some(obj) = v.as_object_mut() {
        obj.insert("messages".into(), Value::Array(hist.iter().map(msg_json).collect()));
        obj.insert("updated".into(), Value::Number(now_ms().into()));
        // 标题写的是**内存里那份**（`put("title", session.title.get())`）：
        // 改名走的是内存 + 文件两处，自动取名只改内存，落盘要在这一步补上。
        if let Some(t) = app.title_of(sid) {
            obj.insert("title".into(), Value::String(t));
        }
        // 模型与工具开关写的也是**引擎当下那一份**（`put("model", settings.model)`）：
        // 按会话换过模型之后，第一回合的落盘才把新值带进文件
        obj.insert("model".into(), Value::String(st.model));
        obj.insert(
            "toolsOff".into(),
            Value::Array(st.tools_off.iter().map(|t| Value::String(t.clone())).collect()),
        );
        // Kotlin 落盘时写的是 `session.workspace.absolutePath`（`File` 的路径）——
        // 这就是正斜杠的 workspace 会在 JVM 侧"落一次盘就变成反斜杠"的原因。
        obj.insert(
            "workspace".into(),
            Value::String(crate::store::norm_ws(&ctx.workspace.to_string_lossy())),
        );
        // 写的是**活值**（`put("promptTokens", totalPrompt)`），不是从文件累加。
        // 于是重启后第一次保存会把文件里的总数改小 —— Kotlin 就这么行为，照抄。
        obj.insert("promptTokens".into(), Value::Number(pt.into()));
        obj.insert("completionTokens".into(), Value::Number(ct.into()));
        let todos: Vec<Value> = ctx
            .todos
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .iter()
            .map(|(t, s)| {
                let mut m = Map::new();
                m.insert("text".into(), Value::String(t.clone()));
                m.insert("status".into(), Value::String(s.clone()));
                Value::Object(m)
            })
            .collect();
        obj.insert("todos".into(), Value::Array(todos));
    }
    atomic_write(&path, &v.to_string());
}

/// `Engine.persistNow()`：回合**之外**也要落一次盘。
///
/// 现在只有一个调用方：`/api/tool` 关掉一把工具之后 Kotlin 立刻存盘，所以那条会话文件里
/// 的 `model`/`toolsOff` 都是引擎当下那一份（而按会话换模型那条**没有**这一句，
/// 于是改了只在内存里，重启就退回文件里的旧值 —— 两半的差别是原样，不是漏写）。
/// 没跑过回合的会话在本进程里没有活历史，那一份 `messages` 就**不动文件**：
/// Kotlin 那边引擎的历史与文件同源，写回去是同一个内容；这里历史是空的，
/// 照着写一次就等于把用户的旧消息抹掉。
pub(crate) fn persist_now(app: &App, sid: &str) {
    let path = app.store.file_for(sid);
    let Ok(raw) = fs::read_to_string(&path) else { return };
    let Ok(mut v) = serde_json::from_str::<Value>(&raw) else { return };
    let st = app.settings_of(sid);
    let (pt, ct) = app.usage_of(sid);
    let hist = app.history(sid);
    if let Some(obj) = v.as_object_mut() {
        obj.insert("updated".into(), Value::Number(now_ms().into()));
        // 与 `persist` 同一件事：工作区写的是 `File.absolutePath` 那一形（反斜杠）。
        // 种子文件里可能是正斜杠进来的，落一次盘就要归一一次，两端才会留下同一个文件。
        obj.insert(
            "workspace".into(),
            Value::String(crate::store::abs_path(&app.store.meta(sid).map(|m| m.workspace).unwrap_or_default())),
        );
        obj.insert("model".into(), Value::String(st.model));
        obj.insert(
            "toolsOff".into(),
            Value::Array(st.tools_off.iter().map(|t| Value::String(t.clone())).collect()),
        );
        // 活计数器是"重启后从 0 重新累计"那份 —— 连"把文件里的总数改小"也一起照抄
        obj.insert("promptTokens".into(), Value::Number(pt.into()));
        obj.insert("completionTokens".into(), Value::Number(ct.into()));
        if let Some(t) = app.title_of(sid) {
            obj.insert("title".into(), Value::String(t));
        }
        if !hist.is_empty() {
            obj.insert("messages".into(), Value::Array(hist.iter().map(msg_json).collect()));
        }
    }
    atomic_write(&path, &v.to_string());
}

/// 消息落盘的形状与 `Engine.persist` 一致：可选键**只在非空/大于 0 时**才写。
fn msg_json(m: &Msg) -> Value {
    let mut o = Map::new();
    o.insert("role".into(), Value::String(m.role.clone()));
    o.insert("content".into(), Value::String(m.content.clone()));
    if !m.call_id.is_empty() {
        o.insert("tool_call_id".into(), Value::String(m.call_id.clone()));
    }
    if !m.name.is_empty() {
        o.insert("name".into(), Value::String(m.name.clone()));
    }
    if !m.reasoning.is_empty() {
        o.insert("reasoning".into(), Value::String(m.reasoning.clone()));
    }
    if !m.diff.is_empty() {
        o.insert("diff".into(), Value::String(m.diff.clone()));
    }
    if !m.images.is_empty() {
        o.insert("images".into(), Value::Array(m.images.iter().map(|x| Value::String(x.clone())).collect()));
    }
    if !m.media.is_empty() {
        o.insert("media".into(), Value::Array(m.media.iter().map(|x| Value::String(x.clone())).collect()));
    }
    if !m.note.is_empty() {
        o.insert("note".into(), Value::String(m.note.clone()));
    }
    if !m.notice.is_empty() {
        o.insert("notice".into(), Value::String(m.notice.clone()));
    }
    if !m.sub.is_empty() {
        o.insert("sub".into(), Value::String(m.sub.clone()));
    }
    if m.pt > 0 {
        o.insert("pt".into(), Value::Number(m.pt.into()));
    }
    if m.ct > 0 {
        o.insert("ct".into(), Value::Number(m.ct.into()));
    }
    if m.ms > 0 {
        o.insert("ms".into(), Value::Number(m.ms.into()));
    }
    if !m.calls.is_empty() {
        let tc: Vec<Value> = m
            .calls
            .iter()
            .map(|(id, name, args)| {
                let mut f = Map::new();
                f.insert("name".into(), Value::String(name.clone()));
                f.insert("arguments".into(), Value::String(args.clone()));
                let mut c = Map::new();
                c.insert("id".into(), Value::String(id.clone()));
                c.insert("type".into(), Value::String("function".into()));
                c.insert("function".into(), Value::Object(f));
                Value::Object(c)
            })
            .collect();
        o.insert("tool_calls".into(), Value::Array(tc));
    }
    Value::Object(o)
}

/// `Env.atomicWrite` 的简化版：先写 `.tmp` 再 rename，失败退回原地覆盖。
/// Kotlin 那边是四级降级（Windows 上别的进程持读句柄时 rename 会 ACCESS_DENIED），
/// 缺的两级只影响并发读窗口，不影响正确性。
fn atomic_write(path: &Path, content: &str) {
    let tmp = path.with_extension("json.tmp");
    if fs::write(&tmp, content.as_bytes()).is_ok() {
        if fs::rename(&tmp, path).is_ok() {
            return;
        }
        let _ = fs::remove_file(&tmp);
    }
    let _ = fs::write(path, content.as_bytes());
}

/// 供测试用的等待：轮询到该会话不再在跑。
#[cfg(test)]
pub fn wait_idle(app: &App, sid: &str, secs: u64) -> bool {
    use std::time::Duration;
    let deadline = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < deadline {
        if !app.is_running(sid) {
            return true;
        }
        thread::sleep(Duration::from_millis(50));
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 卡片副标题取的是"**第一个存在的参数**"，与工具名无关 —— 这条是照 Kotlin 的
    /// `command → path → url → pattern → question` 顺序来的，改回"按工具名查"两端就会分叉。
    #[test]
    fn brief_picks_the_first_present_object_not_the_tool_name() {
        assert_eq!(brief(r#"{"path":"src/a.rs"}"#), "src/a.rs");
        assert_eq!(brief(r#"{"command":"git status"}"#), "git status");
        // command 优先于 path：`git -C` 这类两个都给的写法，副标题讲的是命令
        assert_eq!(brief(r#"{"path":"a","command":"ls"}"#), "ls");
        assert_eq!(brief(r#"{"url":"https://x.dev/a"}"#), "https://x.dev/a");
        assert_eq!(brief(r#"{"pattern":"**/*.kt"}"#), "**/*.kt");
        // 一个都不认识就用整串兜底，取 140 个 UTF-16 单元
        assert_eq!(brief(r#"{"items":[{"text":"第一步","status":"pending"}]}"#),
            "{\"items\":[{\"text\":\"第一步\",\"status\":\"pending\"}]}");
    }

    #[test]
    fn brief_cuts_by_utf16_units_not_chars_or_bytes() {
        // 😀 占两个 UTF-16 单元：`x` + 90 个 emoji + `y` = 182 单元，取 180 落在代理对中间
        let long = format!("x{}y", "😀".repeat(90));
        let b = brief(&format!(r#"{{"path":"{long}"}}"#));
        // 这里与 Kotlin **故意不同**：`substring(0,180)` 会切出一个落单的半个代理码元，
        // 那串东西发进 JSON 流里前端解不出来；`utf16_take` 宁可少一个单元也不切开。
        assert_eq!(crate::utf16::utf16_len(&b), 179, "少一个单元，但不留半个字");
        assert!(!b.ends_with('y'), "截在 180 之前，最后的 y 不该在里面");
        // 计数口径必须是 UTF-16：按 `chars().take(180)` 数会取到 90 个 emoji 之后还有余量，
        // 短一大截 vs 长一大截，差的就是这个
        let by_chars: String = long.chars().take(180).collect::<String>();
        assert!(by_chars.chars().count() > b.chars().count(), "按 char 数会多取");
    }

    /// 数组/对象参数：Kotlin 那句 `jsonPrimitive` 会**抛**，也就是为一行副标题把整轮打崩。
    /// 这里不学那个抛法，退到整串兜底 —— 这是一处**故意不同**，写下来免得被当 bug"修回去"。
    #[test]
    fn a_non_string_first_key_falls_back_instead_of_crashing_the_round() {
        assert_eq!(
            brief(r#"{"path":["a","b"]}"#),
            "{\"path\":[\"a\",\"b\"]}"
        );
    }

    #[test]
    fn subject_is_the_object_the_rollback_button_needs() {
        assert_eq!(subject_of(r#"{"path":"src/a.rs"}"#), "src/a.rs");
        assert_eq!(subject_of(r#"{"command":"rm a"}"#), "rm a");
        // 空白不算：`"path": ""` 要往下找别的键，回滚按钮不能指着"空路径"
        assert_eq!(subject_of(r#"{"path":"  ","file":"b.md"}"#), "b.md");
        assert_eq!(subject_of(r#"{"limit":5}"#), "");
    }
}
