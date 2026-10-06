//! OpenAI 兼容网关的流式调用，移植自 `Provider.kt` 的 `OpenAiProvider`。
//!
//! 请求体的**键序**照抄 Kotlin：`model, temperature, [max_tokens], [reasoning_effort],
//! stream, stream_options, messages, [tools]`。键序不影响语义，但影响
//! `schemas().toString()` 那类按字符串长度算的账，也影响抓包对比时的可读性。
use std::io::{BufRead, BufReader};

use serde_json::{Map, Value, json};

use crate::store::Settings;

pub struct Turn {
    pub text: String,
    pub reasoning: String,
    pub finish: String,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub tool_calls: Vec<(String, String, String)>,
}

/// `Provider.headerKey`：把 apikey 文件按行 trim 后拼起来，并**拦下非 ASCII**。
/// 不拦的话网关只会回一句含糊的鉴权失败，看不出是"复制时把中文带进 key 里了"。
fn header_key(raw: &str) -> Result<String, String> {
    let k: String = raw.lines().map(|l| l.trim()).collect();
    if !k.is_empty() && !k.chars().all(|c| (0x20..0x7f).contains(&(c as u32))) {
        return Err("API key 里有非 ASCII 或控制字符（多半是复制时带进了中文或换行），请重新粘贴一段纯 ASCII 的 key".to_string());
    }
    Ok(k)
}

pub fn endpoint(base_url: &str) -> String {
    format!("{}/chat/completions", base_url.trim_end_matches('/'))
}

/// 把一条消息映射成请求体里的一项（`Provider.msgJson`）。
/// 三个分支的**键序**照抄 Kotlin：有 calls 时是 role/content/tool_calls，
/// tool 角色是 role/tool_call_id/content。
pub fn msg_json(role: &str, content: &str, calls: &[(String, String, String)], call_id: &str) -> Value {
    let mut m = Map::new();
    m.insert("role".into(), json!(role));
    if !calls.is_empty() {
        m.insert("content".into(), json!(content));
        let tc: Vec<Value> = calls
            .iter()
            .map(|(id, name, args)| {
                let mut f = Map::new();
                f.insert("name".into(), json!(name));
                f.insert("arguments".into(), json!(if args.trim().is_empty() { "{}" } else { args }));
                let mut o = Map::new();
                o.insert("id".into(), json!(id));
                o.insert("type".into(), json!("function"));
                o.insert("function".into(), Value::Object(f));
                Value::Object(o)
            })
            .collect();
        m.insert("tool_calls".into(), Value::Array(tc));
    } else if role == "tool" {
        m.insert("tool_call_id".into(), json!(call_id));
        m.insert("content".into(), json!(content));
    } else {
        m.insert("content".into(), json!(content));
    }
    Value::Object(m)
}

/// 请求体。`tools_json` 是已经拼好的 `[{type:function, function:{…}}, …]`。
pub fn body(
    s: &Settings,
    messages: Vec<Value>,
    tools: Vec<Value>,
) -> String {
    let mut m = Map::new();
    m.insert("model".into(), json!(s.model));
    m.insert("temperature".into(), json!(s.temperature));
    if s.max_tokens > 0 {
        m.insert("max_tokens".into(), json!(s.max_tokens));
    }
    if !s.reasoning_effort.trim().is_empty() {
        m.insert("reasoning_effort".into(), json!(s.reasoning_effort));
    }
    m.insert("stream".into(), json!(true));
    let mut so = Map::new();
    so.insert("include_usage".into(), json!(true));
    m.insert("stream_options".into(), Value::Object(so));
    m.insert("messages".into(), Value::Array(messages));
    if !tools.is_empty() {
        m.insert("tools".into(), Value::Array(tools));
    }
    Value::Object(m).to_string()
}

/// 处理一行 SSE。返回 true 表示流结束（`[DONE]`）。
/// 逐字段都只在"确实是字符串"时取：网关在"这帧只有 tool_calls"时会发
/// `"content": null`，把它当字符串取会往历史里塞进字面量 "null"。
fn handle_line(
    line: &str,
    t: &mut Turn,
    on_delta: &mut dyn FnMut(&str),
    on_reason: &mut dyn FnMut(&str),
    slots: &mut Vec<(String, String, String)>,
) -> bool {
    if line.is_empty() || line.starts_with(':') || !line.starts_with("data:") {
        return false;
    }
    let payload = line[5..].trim();
    if payload == "[DONE]" {
        return true;
    }
    let Ok(obj) = serde_json::from_str::<Value>(payload) else {
        return false;
    };
    if let Some(u) = obj.get("usage").and_then(|x| x.as_object()) {
        if let Some(v) = u.get("prompt_tokens").and_then(|x| x.as_i64()) {
            t.prompt_tokens = v;
        }
        if let Some(v) = u.get("completion_tokens").and_then(|x| x.as_i64()) {
            t.completion_tokens = v;
        }
    }
    let Some(choice) = obj.get("choices").and_then(|x| x.as_array()).and_then(|a| a.first().cloned()) else {
        return false;
    };
    if let Some(fr) = choice.get("finish_reason").and_then(|x| x.as_str()) {
        if !fr.is_empty() {
            t.finish = fr.to_string();
        }
    }
    let Some(delta) = choice.get("delta").and_then(|x| x.as_object()).cloned() else {
        return false;
    };
    if let Some(piece) = delta.get("content").and_then(|x| x.as_str()) {
        if !piece.is_empty() {
            t.text.push_str(piece);
            on_delta(piece);
        }
    }
    let r = delta
        .get("reasoning_content")
        .and_then(|x| x.as_str())
        .or_else(|| delta.get("reasoning").and_then(|x| x.as_str()));
    if let Some(r) = r {
        if !r.is_empty() {
            t.reasoning.push_str(r);
            on_reason(r);
        }
    }
    if let Some(tcs) = delta.get("tool_calls").and_then(|x| x.as_array()) {
        for raw in tcs {
            let tc = match raw.as_object() {
                Some(o) => o,
                None => continue,
            };
            let idx = tc
                .get("index")
                .and_then(|x| match x {
                    Value::Number(n) => n.as_i64(),
                    Value::String(s) => s.parse::<i64>().ok(),
                    _ => None,
                })
                .unwrap_or(0) as usize;
            while slots.len() <= idx {
                slots.push((String::new(), String::new(), String::new()));
            }
            if let Some(id) = tc.get("id").and_then(|x| x.as_str()) {
                if !id.is_empty() {
                    slots[idx].0 = id.to_string();
                }
            }
            let f = tc.get("function").and_then(|x| x.as_object());
            if let Some(f) = f {
                if let Some(n) = f.get("name").and_then(|x| x.as_str()) {
                    if !n.is_empty() {
                        slots[idx].1.push_str(n);
                    }
                }
                if let Some(a) = f.get("arguments").and_then(|x| x.as_str()) {
                    slots[idx].2.push_str(a);
                }
            }
        }
    }
    false
}

/// 跑一次流式补全。`on_delta` / `on_reason` 用来把增量往前端推。
pub fn chat(
    s: &Settings,
    api_key_raw: &str,
    body: &str,
    on_delta: &mut dyn FnMut(&str),
    on_reason: &mut dyn FnMut(&str),
) -> Result<Turn, String> {
    let key = header_key(api_key_raw)?;
    if s.base_url.trim().is_empty() {
        return Err("没有配置网关地址（settings.json 的 baseUrl 为空）".to_string());
    }
    let url = endpoint(&s.base_url);
    let mut t = Turn {
        text: String::new(),
        reasoning: String::new(),
        finish: String::new(),
        prompt_tokens: 0,
        completion_tokens: 0,
        tool_calls: vec![],
    };
    let mut slots: Vec<(String, String, String)> = vec![];

    let mut resp = ureq::post(&url)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("Authorization", &format!("Bearer {key}"))
        .send(body)
        .map_err(|e| format!("连接失败：{e}"))?;
    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        let err = resp.body_mut().read_to_string().unwrap_or_default();
        let cut: String = err.chars().take(600).collect();
        return Err(format!("HTTP {status} {cut}"));
    }

    let mut br = BufReader::new(resp.body_mut().as_reader());
    let mut line = String::new();
    loop {
        line.clear();
        match br.read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {}
            Err(_) => break,
        }
        let trimmed = line.trim_end_matches(['\n', '\r']);
        if handle_line(trimmed, &mut t, on_delta, on_reason, &mut slots) {
            break;
        }
    }

    // id 缺失时按序补 call_N；空 arguments 补成 {}，否则网关会当成非法 JSON
    t.tool_calls = slots
        .into_iter()
        .enumerate()
        .map(|(i, (id, name, args))| {
            (
                if id.is_empty() { format!("call_{}", i + 1) } else { id },
                name,
                if args.trim().is_empty() { "{}".to_string() } else { args },
            )
        })
        .filter(|(_, name, _)| !name.is_empty())
        .collect();

    if t.text.trim().is_empty() && t.tool_calls.is_empty() {
        let deterministic = t.finish.eq_ignore_ascii_case("length")
            || t.finish.eq_ignore_ascii_case("content_filter");
        if !deterministic {
            return Err(format!(
                "模型没有返回内容（finish_reason={}）",
                if t.finish.is_empty() { "没有给" } else { &t.finish }
            ));
        }
    }
    Ok(t)
}

/// 供 engine 拼请求用：系统提示 + 会话历史（含工具调用与 tool 回复）。
pub fn request_body(s: &Settings, sys: &str, history: &[crate::store::Msg], plan: bool) -> String {
    let mut msgs = vec![msg_json("system", sys, &[], "")];
    for m in history {
        msgs.push(msg_json(&m.role, &m.content, &m.calls, &m.call_id));
    }
    body(s, msgs, tool_values(s, plan))
}

/// 32 把工具的 function-calling 形状。与 `schemas()` 同源（同一张生成表），
/// 但这里要的是 OpenAI 的 `{type:function, function:{…}}` 包装。
///
/// 过滤链照 `Engine.schemas()`：这条会话的开关 → 档位收窄。
/// 计划模式必须在这里就把写类工具**从模型眼前拿走**，只留执行侧那道闸是不够的 ——
/// 闸只挡住调得动的，挡不住模型调了之后被拒、再试一遍那种白跑。
fn tool_values(s: &Settings, plan: bool) -> Vec<Value> {
    crate::tools::TOOLS
        .iter()
        .filter(|t| !s.tools_off.iter().any(|o| o == t.name))
        .filter(|t| !plan || crate::tools::read_only(t))
        .map(|t| {
            let mut f = Map::new();
            f.insert("name".into(), json!(t.name));
            f.insert("description".into(), json!(t.desc));
            f.insert(
                "parameters".into(),
                serde_json::from_str(t.params).unwrap_or_else(|_| json!({})),
            );
            let mut o = Map::new();
            o.insert("type".into(), json!("function"));
            o.insert("function".into(), Value::Object(f));
            Value::Object(o)
        })
        .collect()
}
