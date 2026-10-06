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
use std::time::{Duration, Instant};

use serde_json::{Map, Value};

use crate::prompt;
use crate::provider;
use crate::state::quote;
use crate::store::Msg;
use crate::tools_impl::{self, Ctx};
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
    let plan = sf.meta.mode == "plan";
    let run_id = format!("r{}", now_ms());
    let ctx = Ctx::for_run(
        &app.store.home,
        workspace,
        app.settings.flags.clone(),
        &sf.meta.mode,
        sid,
        &run_id,
    );
    let pctx = prompt::ctx_for(
        &app.store,
        &sf.meta.workspace,
        &app.settings.model,
        &sf.meta.mode,
        &[],
    );
    let sys = prompt::system(&pctx);

    let mut hist = app.history(sid);
    if hist.is_empty() {
        hist = sf.msgs.clone();
    }
    hist.push(Msg { role: "user".into(), content: text.to_string(), ..Default::default() });
    // 头行：这一轮动了哪些文件要往这本账上记，而"回到这次任务之前"得先知道任务是什么。
    // `at` 是这句在历史里的下标 —— 按时间猜会退错：定时任务、手机派活都往同一条会话里追加消息。
    crate::checkpoints::begin(&app.store.home, sid, &run_id, text, hist.len() as i64 - 1);

    let key_raw = fs::read_to_string(app.store.home.join("apikey")).unwrap_or_default();
    let max_turns = app.settings.max_turns.max(1) as usize;
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
        let body = provider::request_body(&app.settings, &sys, &hist, plan);
        let mut on_delta = |s: &str| app.publish(sid, "delta", &quote(s));
        let mut on_reason = |s: &str| app.publish(sid, "reason", &quote(s));
        let started = Instant::now();

        let t = match provider::chat(&app.settings, &key_raw, &body, &mut on_delta, &mut on_reason)
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
                    app.settings.max_tokens
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
                app.publish(sid, "tool", &tool_end(id, name, false, "已中断，未执行", "generic"));
                continue;
            }
            // 闸口在 ToolStart **之前**（照 Kotlin 的顺序）：被拒的调用没真的动过，
            // 就不该在界面上先转一张"正在执行"的圈再变红。
            let (text, ok) = match refuse(app, name, plan) {
                Some(why) => (why, false),
                None => {
                    app.publish(sid, "tool", &tool_start(id, name, raw_args));
                    exec(name, raw_args, &ctx)
                }
            };
            // 每个 tool_call_id 恰好一条回复，缺一条下一次请求就被网关判 400
            hist.push(Msg {
                role: "tool".into(),
                content: text.clone(),
                call_id: id.clone(),
                name: name.clone(),
                ..Default::default()
            });
            app.set_history(sid, hist.clone());
            app.publish(sid, "tool", &tool_end(id, name, ok, &text, "generic"));
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
        quote(&brief(name, raw_args)),
        quote(&subject_of(name, raw_args))
    )
}

/// `Ev.ToolEnd` → SSE 帧。键序照 `Server.forward`，且 **card 是渲染意图**
/// （generic|terminal|diff），不是工具名 —— 界面按 `d.card=='diff'` 决定要不要挂 diff 统计。
fn tool_end(id: &str, name: &str, ok: bool, out: &str, card: &str) -> String {
    format!(
        "{{\"id\":{},\"name\":{},\"ok\":{},\"card\":{},\"out\":{},\"diff\":\"\",\"note\":\"\",\
         \"sub\":\"\",\"media\":[],\"cites\":[]}}",
        quote(id),
        quote(name),
        ok,
        quote(card),
        quote(out)
    )
}

/// 执行侧的可见性闸口，顺序与 `Engine` 里 `Ev.ToolStart` 之前那三段一致：
/// 未知 → 计划模式 → 这条会话的开关。谁先拒就用谁的措辞。
/// 返回 Some(理由) 表示这一步**不做**，理由同时是回给模型的 tool 内容。
fn refuse(app: &App, name: &str, plan: bool) -> Option<String> {
    if !crate::tools::TOOLS.iter().any(|t| t.name == name) {
        let avail = crate::tools::TOOLS.iter().map(|t| t.name).collect::<Vec<_>>().join(",");
        return Some(format!("未知工具：{name}。可用的是 {avail}"));
    }
    if plan && !tool_is_read_only(name) {
        return Some(format!(
            "计划模式是只读的，{name} 不能用。把要做的事写进计划，或用 ask_user 确认切档。"
        ));
    }
    if app.settings.tools_off.iter().any(|o| o == name) {
        return Some(format!("工具 {name} 在这条会话里被关掉了：右栏「工具」页签可以重新打开。"));
    }
    None
}

/// 过闸之后才轮到真执行。
fn exec(name: &str, raw_args: &str, ctx: &Ctx) -> (String, bool) {
    let args: Value = serde_json::from_str(raw_args).unwrap_or(Value::Object(Map::new()));
    match tools_impl::dispatch(ctx, name, &args) {
        Some(o) => (o.text, o.ok),
        None => (
            format!(
                "工具 {name} 尚未移植到 Rust 引擎（M2d 目前只装了 read/glob/grep/todo）。请换个能用的方案，或改用 Kotlin 引擎跑这类操作。"
            ),
            false,
        ),
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

/// `ToolBrief` 的极简版：卡片标题取主参数。
fn brief(name: &str, args: &str) -> String {
    let v: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let key = match name {
        "read" => "path",
        "glob" | "grep" => "pattern",
        "todo" => "items",
        _ => "",
    };
    if key == "items" {
        let n = v.get("items").and_then(|x| x.as_array()).map(|a| a.len()).unwrap_or(0);
        return format!("更新清单 · {n} 项");
    }
    let got = v.get(key).and_then(|x| x.as_str()).unwrap_or("");
    let cut: String = got.chars().take(40).collect();
    format!("{name} · {cut}")
}

fn subject_of(name: &str, args: &str) -> String {
    let v: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    match name {
        "read" | "write" | "edit" => v.get("path").and_then(|x| x.as_str()).unwrap_or("").to_string(),
        _ => String::new(),
    }
}

/// 读回原文件、只改自己负责的键、再原子写回。保留未知键与键序（`preserve_order`）。
fn persist(app: &App, sid: &str, hist: &[Msg], ctx: &Ctx) {
    let path = app.store.home.join("sessions").join(format!("pc-{sid}.json"));
    let raw = fs::read_to_string(&path).unwrap_or_default();
    let mut v: Value = serde_json::from_str(&raw).unwrap_or_else(|_| Value::Object(Map::new()));
    let (pt, ct) = app.usage_of(sid);
    if let Some(obj) = v.as_object_mut() {
        obj.insert("messages".into(), Value::Array(hist.iter().map(msg_json).collect()));
        obj.insert("updated".into(), Value::Number(now_ms().into()));
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
#[allow(dead_code)]
pub fn wait_idle(app: &App, sid: &str, secs: u64) -> bool {
    let deadline = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < deadline {
        if !app.is_running(sid) {
            return true;
        }
        thread::sleep(Duration::from_millis(50));
    }
    false
}
