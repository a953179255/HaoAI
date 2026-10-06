use crate::store::{SessionFile, Settings, Store};
use crate::utf16::utf16_len;
use crate::PC_VERSION;

/// 与 `Server.esc` 逐字符同行为。**不能换成 serde 序列化**：Kotlin 那份是手写转义，
/// `\r` 是**整字符删掉**、`\t` 变成四个空格，而 serde 会输出 `\r` / `\t` 转义序列。
/// `/api/state` 与盘上格式的字节只要差一个，回放测试与两端同一份 MEMORY.md 行格式就对不上。
pub fn esc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            '\t' => out.push_str("    "),
            _ => out.push(c),
        }
    }
    out
}

pub fn quote(s: &str) -> String {
    format!("\"{}\"", esc(s))
}

/// 帧格式与 `Server.write` 一致：`event: <名>\ndata: <负载>\n\n`，
/// 有 sid 时前置 `id: <sid>\n`（浏览器把它挂到 `MessageEvent.lastEventId` 上，
/// 于是"这条事件属于哪个会话"不用改任何已有负载的格式就能带出去）。
pub fn sse_frame(event: &str, data: &str, sid: &str) -> String {
    let head = if sid.is_empty() { String::new() } else { format!("id: {sid}\n") };
    format!("{head}event: {event}\ndata: {data}\n\n")
}

pub struct View<'a> {
    pub settings: &'a Settings,
    /// 当前驻留的会话；`None` = 没有任何会话驻留（此时各字段回落到全局设置）
    pub session: Option<&'a SessionFile>,
    pub session_id: &'a str,
    /// 算系统提示要读状态根里的技能/子智能体/说明文件
    pub store: &'a Store,
    /// 这条会话此刻是否在跑回合（`/api/state` 的 `running` 与停止按钮靠它）
    pub running: bool,
    /// 进程内累计的 token 活计数（`Engine.totalPrompt/totalCompletion`）
    pub usage: (i64, i64),
    /**
     * 挂着没答的审批/提问（`approvals.pendingFor(id)`）。
     *
     * 它们本来只通过 SSE 推一次：页面一刷新引擎还卡在等一个不会再出现的按钮，
     * 表现是"agent 莫名卡死"，最后超时自动拒掉。审批是消息流里的内联卡，
     * 一条会话摆一张，所以"只回第一条"就变成了**另一条没人管** —— 这里全交。
     */
    pub pending: &'a [crate::approval::Waiter],
}

/// `Server.stateJson` 的移植。**键的顺序与写法逐字对齐**（含 role/status 那两处
/// 故意不过 esc 的裸插入），值分两类：
/// - 文件可导出的：mode/workspace/model/preset/title/role/sessionId/todos/messages → 已填实；
/// - 依赖引擎运行态的：tools（32 条工具注册表）/runState/subs/context.parts（要跑提示构造器）
///   /usage（引擎回合内实时计数器，restore 后从 0 起）→ 仍按"未驻留"给安全值，
///   差异由 tests/diff_golden.rs 逐条列账，不假装已经会。
/// `Images.wireChars`：这张图大概要占多少"字"（上下文占用那圈要用它来算）。
/// 按路径的 40 个字符算就是骗人 —— 一张截图的真实成本是 base64 之后的那几 MB。
/// 读不到文件就 0（那张图压根发不出去，不算成本）。
const WIRE_MAX_BYTES: u64 = 6_000_000;
fn wire_chars(path: &str) -> usize {
    let Ok(m) = std::fs::metadata(path) else { return 0 };
    if !m.is_file() {
        return 0;
    }
    let n = m.len().min(WIRE_MAX_BYTES);
    // base64 长度 = ceil(n/3)*4，再加 24 个字符的 `data:…;base64,` 前缀
    (((n + 2) / 3) * 4 + 24) as usize
}

pub fn state_json(v: &View) -> String {
    let meta = v.session.map(|s| &s.meta);
    let mode = meta.map(|m| m.mode.as_str()).unwrap_or(&v.settings.permission_mode);
    let workspace = meta
        .map(|m| m.workspace.as_str())
        .filter(|t| !t.is_empty())
        .unwrap_or(&v.settings.workspace);
    let model = meta
        .map(|m| m.model.as_str())
        .filter(|t| !t.is_empty())
        .unwrap_or(&v.settings.model);
    let preset = meta.map(|m| m.preset.as_str()).unwrap_or("");
    let title = meta.map(|m| m.title.as_str()).unwrap_or("新会话");
    let role = meta.map(|m| m.role.as_str()).unwrap_or("");

    let mut s = String::new();
    s.push('{');
    s.push_str(&format!("\"mode\":{},", quote(mode)));
    s.push_str(&format!("\"workspace\":{},", quote(workspace)));
    s.push_str(&format!("\"model\":{},", quote(model)));
    s.push_str(&format!("\"preset\":{},", quote(preset)));
    s.push_str("\"queue\":[],");
    // off = 全局 toolsOff ∪ 该会话 toolsOff（`AgentConfigs.effectiveToolsOff` 的两层合成）
    let mut off: Vec<&str> = v.settings.tools_off.iter().map(|x| x.as_str()).collect();
    if let Some(m) = meta {
        for t in &m.tools_off {
            if !off.contains(&t.as_str()) {
                off.push(t.as_str());
            }
        }
    }
    s.push_str("\"tools\":[");
    s.push_str(&crate::tools::tools_json(&off, &v.settings.flags));
    s.push_str("],");
    s.push_str("\"runState\":null,");
    s.push_str("\"subs\":[],");
    s.push_str(&format!("\"version\":{},", quote(PC_VERSION)));
    s.push_str(&format!("\"modelNow\":{},", quote(model)));
    s.push_str(&format!("\"title\":{},", quote(title)));
    s.push_str(&format!("\"role\":{},", quote(role)));
    s.push_str(&format!("\"sessionId\":{},", quote(v.session_id)));
    s.push_str(&format!("\"running\":{},", v.running));

    s.push_str("\"todos\":[");
    if let Some(sf) = v.session {
        for (i, t) in sf.todos.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            // status 与下面 messages 的 role 一样，Kotlin 是裸拼进去的，不走 esc
            s.push_str(&format!("{{\"text\":{},\"status\":\"{}\"}}", quote(&t.text), t.status));
        }
    }
    s.push_str(&format!(
        "],\"usage\":{{\"prompt\":{},\"completion\":{}}},",
        v.usage.0, v.usage.1
    ));

    // `Engine.contextBreakdown()`：只为**数值为正**的项建行，顺序固定
    // 系统提示 → 项目说明 → 工具说明 → 对话历史 → 前情摘要。
    // 系统提示要等提示构造器（M2b）才算得出，现在为 0 就**不建行**——这恰好复用 Kotlin
    // 对 sys/mem 为 0 时的同一套规则，所以形状仍然合法，只是少一行。
    let pctx = crate::prompt::ctx_for(
        v.store,
        workspace,
        model,
        mode,
        &[],
    );
    let sys_full = crate::prompt::system(&pctx);
    let sys_chars = utf16_len(&sys_full);
    // `mem = Memory.read(...).length.coerceAtMost(sys)`，且 sys 那一行报的是**减掉 mem 之后**的数
    let mem_chars = utf16_len(pctx.extra.trim()).min(sys_chars);
    let tools_chars = crate::tools::schemas_chars(&off);
    /*
     * Kotlin 那边是三段加起来（`contextBreakdown`）：
     *   content.length + Σ calls.args.length + Σ Images.wireChars(images)
     *
     * **只算 content 是错的**：一条 agent 回合必然带 tool_calls，那 23 字的 args
     * 不算，界面上"历史占多少"就一直偏低 —— 而这一行正是"上下文快满了"的判据。
     * 金标准抓不到它是因为那批会话没有一条带 tool_calls 的消息，是跨端对跑才暴露的。
     */
    let hist_chars: usize = v
        .session
        .map(|sf| {
            sf.msgs
                .iter()
                .filter(|m| m.role != "system")
                .map(|m| {
                    let args: usize = m.calls.iter().map(|(_, _, a)| utf16_len(a)).sum();
                    let images: usize = m.images.iter().map(|p| wire_chars(p)).sum();
                    utf16_len(&m.content) + args + images
                })
                .sum()
        })
        .unwrap_or(0);
    let mut parts: Vec<String> = vec![];
    if sys_chars > mem_chars {
        parts.push(format!("[{},{}]", quote("系统提示"), sys_chars - mem_chars));
    }
    if mem_chars > 0 {
        parts.push(format!("[{},{}]", quote("项目说明"), mem_chars));
    }
    if tools_chars > 0 {
        parts.push(format!("[{},{}]", quote("工具说明"), tools_chars));
    }
    if hist_chars > 0 {
        parts.push(format!("[{},{}]", quote("对话历史"), hist_chars));
    }
    let ctx_parts = format!("[{}]", parts.join(","));
    let ctx_total = sys_chars.saturating_sub(mem_chars) + mem_chars + tools_chars + hist_chars;

    s.push_str(&format!(
        "\"context\":{{\"chars\":{},\"window\":{},\"trigger\":{},\"compactedThrough\":0,\"parts\":{}}},",
        ctx_total, v.settings.context_chars, v.settings.compact_trigger_chars, ctx_parts
    ));

    s.push_str("\"pending\":[");
    for (i, w) in v.pending.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        // payload 是**已成型的一段 JSON**，按原样拼进去（Kotlin 那边同样不 quote）
        s.push_str(&format!("{{\"ev\":{},\"data\":{}}}", quote(w.ev), w.payload));
    }
    s.push_str("],");

    s.push_str("\"messages\":[");
    if let Some(sf) = v.session {
        for (i, m) in sf.msgs.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            // name 的兜底与 Kotlin 同：本条没名字就用第一个调用的名字
            let name = if m.name.is_empty() {
                m.calls.first().map(|c| c.1.clone()).unwrap_or_default()
            } else {
                m.name.clone()
            };
            let calls = m
                .calls
                .iter()
                .map(|(_, n, a)| format!("{{\"name\":{},\"args\":{}}}", quote(n), quote(a)))
                .collect::<Vec<_>>()
                .join(",");
            let arr = |v: &Vec<String>| {
                format!("[{}]", v.iter().map(|x| quote(x)).collect::<Vec<_>>().join(","))
            };
            s.push_str(&format!(
                "{{\"role\":\"{}\",\"content\":{},\"name\":{},\"index\":{},\"cid\":{},\"reasoning\":{},\
                 \"diff\":{},\"note\":{},\"notice\":{},\"sub\":{},\"images\":{},\"media\":{},\
                 \"pt\":{},\"ct\":{},\"ms\":{},\"calls\":[{}]}}",
                m.role,
                quote(&m.content),
                quote(&name),
                i,
                quote(&m.call_id),
                quote(&m.reasoning),
                quote(&m.diff),
                quote(&m.note),
                quote(&m.notice),
                quote(&m.sub),
                arr(&m.images),
                arr(&m.media),
                m.pt,
                m.ct,
                m.ms,
                calls
            ));
        }
    }
    s.push_str("],\"cites\":[]");
    s.push('}');
    s
}

/// `Server.sessionsJson` 的移植（不带 `?q=` 搜索：`SessionIndex.match` 依赖引擎内历史，另议）。
pub fn sessions_json(rows: &[crate::store::Meta], current: &str) -> String {
    let mut out = String::from("[");
    for (i, m) in rows.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(&format!(
            "{{\"id\":{},\"title\":{},\"workspace\":{},\"mode\":\"{}\",\"updated\":{},\"messages\":{},\
             \"prompt\":{},\"completion\":{},\"running\":false,\"pinned\":{},\"current\":{},\
             \"hit\":{},\"hitAt\":-1}}",
            quote(&m.id),
            quote(&m.title),
            quote(&m.workspace),
            m.mode,
            m.updated,
            m.messages,
            m.prompt,
            m.completion,
            m.pinned,
            m.id == current,
            quote("")
        ));
    }
    out.push(']');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn esc_matches_kotlin_semantics() {
        assert_eq!(esc("a\\b"), "a\\\\b");
        assert_eq!(esc("说\"话\""), "说\\\"话\\\"");
        assert_eq!(esc("l1\nl2"), "l1\\nl2");
        assert_eq!(esc("a\rb"), "ab");
        assert_eq!(esc("a\tb"), "a    b");
    }

    /// `contextBreakdown` 的「对话历史」= `content + Σ calls.args + Σ wireChars(images)`。
    /// **只算 content 是错的**：一轮 agent 必带 tool_calls，那串 args 不算，界面上
    /// "历史占多少"就一直偏低 —— 而这行正是"上下文快满了"的判据。
    /// 金标准抓不到它是因为那批会话没有一条带 tool_calls 的消息；是跨端对跑才暴露的。
    #[test]
    fn history_chars_count_tool_call_args_and_image_wires() {
        use crate::store::Msg;
        let st = Settings::default();
        let store = crate::store::Store::new(std::env::temp_dir());
        let img = std::env::temp_dir().join(format!("haoai-wire-{}.png", std::process::id()));
        std::fs::write(&img, vec![0u8; 100]).unwrap();
        let args = r#"{"path":"a.rs"}"#;
        let sf = crate::store::SessionFile {
            meta: crate::store::Meta::default(),
            msgs: vec![
                Msg { role: "user".into(), content: "abc".into(), ..Default::default() },
                Msg {
                    role: "assistant".into(),
                    content: "读一下".into(),
                    calls: vec![("c1".into(), "read".into(), args.to_string())],
                    ..Default::default()
                },
                // 系统提示**不进**历史（Kotlin 是 filter { role != "system" }）
                Msg { role: "system".into(), content: "这一段不该被算进去".into(), ..Default::default() },
                Msg {
                    role: "user".into(),
                    content: "看图".into(),
                    images: vec![img.to_string_lossy().to_string()],
                    ..Default::default()
                },
            ],
            todos: vec![],
            summary: None,
            compacted_through: 0,
        };
        let s = state_json(&View {
            settings: &st,
            session: Some(&sf),
            session_id: "",
            store: &store,
            running: false,
            usage: (0, 0),
            pending: &[],
        });
        let j: serde_json::Value = serde_json::from_str(&s).unwrap();
        let parts = j["context"]["parts"].as_array().unwrap();
        let hist = parts
            .iter()
            .find(|p| p[0] == "对话历史")
            .expect("历史那行该有");
        let expected = utf16_len("abc") + utf16_len("读一下") + utf16_len(args)
            + utf16_len("看图")
            // 图片按**编码后**的真实成本算，不是路径那 40 个字符：100 字节 → ceil(100/3)*4 + 24
            + (((100 + 2) / 3) * 4 + 24);
        assert_eq!(hist[1], expected, "content+args+wire 才是 Kotlin 那个数：{hist}");
        let _ = std::fs::remove_file(&img);
    }

    #[test]
    fn key_order_matches_statejson_source() {
        let st = Settings::default();
        let store = crate::store::Store::new(std::env::temp_dir());
        let s = state_json(&View { settings: &st, session: None, session_id: "", store: &store, running: false, usage: (0, 0), pending: &[] });
        assert!(s.starts_with("{\"mode\":\"ask\""));
        assert!(s.ends_with("\"messages\":[],\"cites\":[]}"));
        let want = [
            "\"mode\":", "\"workspace\":", "\"model\":", "\"preset\":", "\"queue\":[", "\"tools\":[",
            "\"runState\":null", "\"subs\":[", "\"version\":", "\"modelNow\":", "\"title\":", "\"role\":",
            "\"sessionId\":", "\"running\":false", "\"todos\":[", "\"usage\":{", "\"context\":{",
            "\"pending\":[", "\"messages\":[", "\"cites\":[",
        ];
        let mut at = 0usize;
        for k in want {
            let i = s.find(k).unwrap_or_else(|| panic!("缺键：{k}"));
            assert!(i >= at, "键序错了：{k}");
            at = i;
        }
    }
}
