//! 差分测试器：拿 JVM 引擎采的 `/api/state` 与 `/api/sessions` 金标准逐字节比。
//!
//! 金标准不在仓库里（那是真实对话内容），采法见记忆：复制状态根 → 用
//! `HAOAI_HOME` 覆盖无头跑 jpackage 引擎 → curl 存盘。跑法：
//!
//! ```text
//! HAOAI_GOLDEN_HOME=G:/Rust/golden HAOAI_GOLDEN=G:/Rust/golden-states cargo test --release -- --nocapture
//! ```
//!
//! 缺环境变量就跳过，不会把别人的机器弄红。
#![cfg(test)]

use std::env;
use std::fs;
use std::path::PathBuf;

use crate::state::{self, View};
use crate::store::Store;

/// 金标准门禁：**整份 `/api/state` 逐字节全等**，没有例外。
/// （M1 时曾允许 `context` 段缺失，M2b 把提示构造器补上之后例外已取消。）
#[test]
fn state_matches_jvm_golden() {
    let (Ok(home), Ok(dir)) = (env::var("HAOAI_GOLDEN_HOME"), env::var("HAOAI_GOLDEN")) else {
        eprintln!("skip: 未设 HAOAI_GOLDEN_HOME / HAOAI_GOLDEN");
        return;
    };
    let store = Store::new(PathBuf::from(home));
    let settings = store.settings();

    let mut failed = 0;
    for name in ["default.json", "state-pc49062a4c.json"] {
        let p = PathBuf::from(&dir).join(name);
        let Ok(raw) = fs::read_to_string(&p) else {
            eprintln!("skip {name}: 读不到");
            continue;
        };
        let sid = serde_json::from_str::<serde_json::Value>(&raw)
            .ok()
            .and_then(|v| v.get("sessionId").and_then(|x| x.as_str()).map(String::from))
            .unwrap_or_default();

        let sf = store.restore(&sid);
        let view = View { settings: &settings, session: sf.as_ref(), session_id: &sid, store: &store, running: false, usage: (0, 0), pending: &[] };
        let mine = state::state_json(&view);

        if mine == raw {
            eprintln!("PASS {name}  ({} bytes) 逐字节全等", raw.len());
        } else {
            failed += 1;
            let (i, ca, cb) = first_diff(&raw, &mine);
            eprintln!("FAIL {name}  jvm={} rust={} 首个差异在第 {i} 字节", raw.len(), mine.len());
            eprintln!("  JVM : ...{ca}...");
            eprintln!("  Rust: ...{cb}...");
        }
    }
    assert_eq!(failed, 0, "与金标准不一致");
}

/// `/api/sessions` 是纯文件导出的，没有 context 那种引擎层借口 → 要求逐字节全等。
#[test]
fn sessions_match_jvm_golden() {
    let (Ok(home), Ok(dir)) = (env::var("HAOAI_GOLDEN_HOME"), env::var("HAOAI_GOLDEN")) else {
        eprintln!("skip: 未设 HAOAI_GOLDEN_HOME / HAOAI_GOLDEN");
        return;
    };
    let p = PathBuf::from(&dir).join("sessions.json");
    let Ok(raw) = fs::read_to_string(&p) else {
        eprintln!("skip: 读不到 {}", p.display());
        return;
    };
    let store = Store::new(PathBuf::from(home));
    let cur = store.list(5).first().map(|m| m.id.clone()).unwrap_or_default();
    let mine = state::sessions_json(&store.list(200), &cur);
    assert_eq!(mine, raw, "/api/sessions 与金标准不一致");
    eprintln!("PASS sessions.json  ({} bytes, 逐字节全等)", raw.len());
}

/// `context.parts` 逐行对：金标准里有的行，我们**只要算得出来就必须一字不差**；
/// 算不出来的（系统提示 = 提示构造器，M2b）允许整行缺失，但**不允许出现且数值不对**。
#[test]
fn context_parts_match_jvm() {
    let (Ok(home), Ok(dir)) = (env::var("HAOAI_GOLDEN_HOME"), env::var("HAOAI_GOLDEN")) else {
        eprintln!("skip: 未设 HAOAI_GOLDEN_HOME / HAOAI_GOLDEN");
        return;
    };
    let store = Store::new(PathBuf::from(home));
    let settings = store.settings();
    let p = PathBuf::from(&dir).join("default.json");
    let Ok(raw) = fs::read_to_string(&p) else {
        eprintln!("skip: 读不到 {}", p.display());
        return;
    };
    let jvm: serde_json::Value = serde_json::from_str(&raw).unwrap();
    let sid = jvm["sessionId"].as_str().unwrap_or("").to_string();
    let sf = store.restore(&sid);
    let mine: serde_json::Value =
        serde_json::from_str(&state::state_json(&View { settings: &settings, session: sf.as_ref(), session_id: &sid, store: &store, running: false, usage: (0, 0), pending: &[] }))
            .unwrap();

    let rows = |v: &serde_json::Value| -> Vec<(String, i64)> {
        v["context"]["parts"]
            .as_array()
            .map(|a| a.iter().map(|x| (x[0].as_str().unwrap().to_string(), x[1].as_i64().unwrap())).collect())
            .unwrap_or_default()
    };
    let (jrows, mrows) = (rows(&jvm), rows(&mine));
    for (label, want) in &jrows {
        match mrows.iter().find(|(l, _)| l == label) {
            Some((_, got)) => assert_eq!(want, got, "「{label}」字数与 JVM 不一致"),
            None => assert_eq!(
                label.as_str(),
                "系统提示",
                "只允许「系统提示」整行缺失（提示构造器未移植），其它行必须算得出"
            ),
        }
    }
    eprintln!("PASS context.parts  JVM={jrows:?}  Rust={mrows:?}");
}

/// 返回（首个差异字节下标，两侧各自的上下文）。按 char 边界切片避免切坏中文。
fn first_diff(a: &str, b: &str) -> (usize, String, String) {
    let ab = a.as_bytes();
    let bb = b.as_bytes();
    let mut i = 0;
    while i < ab.len() && i < bb.len() && ab[i] == bb[i] {
        i += 1;
    }
    (i, around(a, i), around(b, i))
}

fn around(s: &str, i: usize) -> String {
    let mut start = i.min(s.len());
    while start > 0 && !s.is_char_boundary(start) {
        start -= 1;
    }
    let mut end = (start + 90).min(s.len());
    while end > start && !s.is_char_boundary(end) {
        end -= 1;
    }
    s[start..end].replace('\n', "\\n")
}
