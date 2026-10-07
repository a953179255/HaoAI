//! 统一闸口：先查规则表（S2），再落到档位，最后才轮到弹卡。
//! 移植自 `Tools.kt` 的 `ToolCtx.guardCore` + `Server.kt` 的 `webGate.approveRule`。
//!
//! **顺序很要紧 —— 规则先于档位**，否则 `auto` 会把用户专门写下的"这条要问我"给跳过。
//! 反过来 `plan` 是最高优先级：计划模式就是只读，规则表也放行不了写。
use std::path::PathBuf;

use crate::approval::approval_payload;
use crate::flags::{self, Flag};
use crate::policies::{Decision, Rule};
use crate::risk::{self, Risk};
use crate::state::quote;
use crate::tools_impl::Ctx;
use crate::App;

/// 返回 `Some(理由)` = 这一步**不做**，那句理由同时是回给模型的 tool 内容。
/// 措辞逐字照抄 Kotlin：模型靠这些句子决定"换方案"还是"问用户"。
pub(crate) fn guard(
    app: &App,
    ctx: &Ctx,
    tool: &str,
    subject: &str,
    title: &str,
    detail: &dyn Fn() -> String,
    subject_is_path: bool,
) -> Option<String> {
    let kind = if tool == "shell" { "exec" } else { "write" };
    let mode = app.mode_of(&ctx.sid, &ctx.mode);
    let verdict = app.policies_with(|s| s.decide(&ctx.workspace, tool, subject));

    if let Some(v) = &verdict {
        if v.decision == Decision::Deny {
            return Some(format!(
                "规则拒绝：{}。这条被显式禁掉了，别再重试，换方案或用 ask_user 问用户。",
                v.why
            ));
        }
    }
    if mode == "plan" {
        return Some(format!("计划模式（只读）下拒绝执行「{title}」。要动手请先切到 ask/auto 档位。"));
    }
    if let Some(v) = &verdict {
        if v.decision == Decision::Allow {
            return None;
        }
    }
    // browser/screen 传的是 URL、坐标、控件名，`subject_is_path` 必须给 false ——
    // 否则下面那句"在工作区之外"会把一个 URL 当路径判出来，在审批卡上写一句驴唇不对马嘴的话。
    // 而且**只有写类才去 resolve**：shell 的 subject 是整条命令，拿它 canonicalize 一遍
    // 既不产生结果也不该有副作用（Kotlin 那边靠 `&&` 的短路做到同一件事）。
    let writeish = subject_is_path && kind == "write";
    let resolved = if writeish { ctx.resolve(subject) } else { PathBuf::new() };
    let out = writeish && ctx.outside(&resolved);
    let exists = writeish && resolved.is_file();
    let rating = risk::of(tool, subject, &detail(), out, exists);
    // 用户显式开了"允许写到工作区外"之后，"在外面"这一项不再计入风险；
    // 但**只摘掉这一项**：强推、删文件、覆盖已有文件那些照样拦。
    // 不这么做的话这个开关就成了"关掉整个审批"的别名，而它的名字只承诺了"允许写到外面"。
    let out_allowed = out && flags::enabled(Flag::OutsideWrite, &ctx.flags);
    let effective =
        if out_allowed { risk::of(tool, subject, &detail(), false, exists) } else { rating };
    /*
     * 沙箱第一层：**auto 档不许往工作区外写**（要往外卖得自己在设置里开）。
     * 定时任务与任务链都以 auto 档跑，没人看卡：原来会弹卡等 300 秒然后自动拒，
     * 模型收到的那句是"超时未答，按拒绝处理" —— 看不出是边界问题还是网关问题，
     * 于是它会换个写法再试一次。当场拒 + 说清为什么 + 说清怎么显式允许才是这一档该有的样子。
     * ask 档**故意不改**：人在电脑前，弹卡让他点是对的，凭什么替他决定。
     */
    let boundary_tool = tool == "write" || tool == "edit";
    if out && boundary_tool && mode == "auto" && !out_allowed {
        /*
         * 顺序是量出来的：工具结果那一行在界面上只看得见前九十个字符
         * （截图里第一版把绝对路径写在第二句，结果"怎么办"整段被截没）。
         * 所以：**结论 → 怎么办 → 才是要动哪个文件**。也不用 `**加粗**`：这行按纯文本渲染。
         */
        return Some(format!(
            "已拒绝：这条要写到工作区之外。要允许就开 haoai flags on outside_write；\
             或把路径改到工作区里。别原样重试，也别改用 shell 绕（那层这规则管不到，但风险闸照样拦）。\
             被拒的路径：{subject}"
        ));
    }
    /*
     * auto 档跳过审批，但**高危不跳**：链里完全可能出现 `git push --force` 或
     * "删工作区外的文件"，以前 auto 就是"全放行"，等于把不可逆的那一下也交给了模型自己决定。
     * 规则表说 ASK 的也不跳 —— 那是用户专门写的"这条要问我"。
     */
    if mode == "auto"
        && effective.level != Risk::High
        && verdict.as_ref().map(|v| v.decision) != Some(Decision::Ask)
    {
        return None;
    }
    let extra = if out { "（在工作区之外）" } else { "" };
    let why = match &verdict {
        Some(v) => format!("\n为什么还要问：{}", v.why),
        None => String::new(),
    };
    let pattern = if tool == "shell" { risk::command_prefix(subject) } else { subject.to_string() };
    let ok = approve_rule(app, ctx, title, &format!("{}{extra}{why}", detail()), kind, tool, &pattern, &effective);
    if ok {
        None
    } else {
        Some(format!("用户拒绝了这次「{title}」。不要原样重试，换个方案或用 ask_user 问清楚。"))
    }
}

/// 推卡 → 等人答 → 把结论翻成界面上那一行小字。
/// 逐块勾选（`hunks`）还没搬：手机上点的永远是整条决定，那条路本来就是默认。
fn approve_rule(
    app: &App,
    ctx: &Ctx,
    title: &str,
    detail: &str,
    kind: &str,
    tool: &str,
    pattern: &str,
    rating: &risk::Verdict,
) -> bool {
    let sid = ctx.sid.clone();
    let mut publish = |ev: &str, data: &str, s: &str| app.publish(s, ev, data);
    let res = app.approvals.await_approval(
        &sid,
        &mut publish,
        &|id| approval_payload(id, title, detail, kind, tool, pattern, Some(rating)),
    );
    // 结论挂到紧接着落的那条工具消息上：之前它只随 SSE 流一次，刷新之后卡没了，
    // 于是"这个文件到底是用户点头写的、还是自动写的、还是超时被拒的"查不出来。
    app.mark_approval(
        &sid,
        if res.timed_out {
            "超时未答，按拒绝处理"
        } else {
            match res.value.as_str() {
                "allow_once" => "允许一次",
                "allow_session" => "本任务都允许",
                "allow_rule" => "写入规则并允许",
                _ => "已拒绝",
            }
        },
    );
    // allow_rule：把这条规则永久写进当前工作区的规则表（S2）
    if res.value == "allow_rule" && !tool.is_empty() {
        app.policies_with(|s| {
            s.add(&ctx.workspace, Rule { tool: tool.to_string(), pattern: pattern.to_string(), decision: Decision::Allow })
        });
        app.publish(&sid, "notice", &quote(&format!("已记住规则：{tool}({pattern})")));
    }
    matches!(res.value.as_str(), "allow_once" | "allow_session" | "allow_rule")
}

/// `Tool.flag` + `visibleWhen`：**关着就等于工具不存在**（不进 schema），
/// 不是"存在但报错说没权限"。理由是能控制浏览器 = 能以你的身份点网页上的任何按钮，
/// 这一步必须显式打开；打开之后每次 navigate/click/type 仍会过上面那道闸。
///
/// MCP 那批工具是动态注册的（不在生成表里），由 `mcp.json` 那边自己判这个开关。
pub(crate) fn flag_for(tool: &str) -> Option<Flag> {
    match tool {
        "browser" => Some(Flag::BrowserControl),
        "screen" => Some(Flag::DesktopControl),
        _ => None,
    }
}

/// `Engine.schemas()` 的第一道过滤：实验特性开关（关着=不存在）。
pub(crate) fn visible(tool: &str, flags: &[(String, bool)]) -> bool {
    match flag_for(tool) {
        Some(f) => flags::enabled(f, flags),
        None => true,
    }
}

/// `toolInfos()` 里那个 `gated`：界面上要说清"这把不是被关掉的，是实验开关挡着的"，
/// 否则用户会在「工具」页签里找那个根本不存在的开关。
pub fn gated(tool: &str, flags: &[(String, bool)]) -> bool {
    flag_for(tool).map(|f| !flags::enabled(f, flags)).unwrap_or(false)
}

/// `AgentConfigs.CRITICAL`：关掉这五把等于砍掉引擎，所以关闭请求**不认**、
/// 界面上也不许亮成"关着"。名单本身在生成的 `tools.rs` 里（`critical` 字段），
/// 这里只是按键查一次的薄封装 —— 表只有一份，两端不会各写一套。
pub fn is_critical(tool: &str) -> bool {
    crate::tools::TOOLS.iter().any(|t| t.name == tool && t.critical)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policies::{Decision, Rule};
    use crate::tools_impl::Ctx;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::{Arc, Mutex};

    fn app_in(dir: &PathBuf) -> Arc<App> {
        // 2 秒的审批超时：测试里"没人答"这条路要真跑到，又不能等 300 秒
        App::at(dir.clone())
    }

    fn pair(tag: &str) -> (PathBuf, PathBuf, Arc<App>) {
        let root = std::env::temp_dir().join(format!("haoai-guard-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("ws/src")).unwrap();
        fs::write(root.join("ws/src/a.rs"), "旧内容\n").unwrap();
        fs::create_dir_all(root.join("home")).unwrap();
        fs::write(root.join("home/settings.json"), r#"{"baseUrl":"http://x/v1","model":"m"}"#).unwrap();
        let ws = root.join("ws");
        let home = root.join("home");
        (root, ws, app_in(&home))
    }

    fn ctx(ws: &PathBuf, mode: &str, flags: Vec<(String, bool)>) -> Ctx {
        Ctx::for_run(&ws.join("home"), ws.clone(), flags, mode, "g", "r1")
    }

    /// 这条是整套东西的意义所在：ask 档下改一个工作区里的文件不该弹卡，
    /// 而 `git push --force` 必须弹 —— 分级不落到"问不问"上就只是文案。
    #[test]
    fn auto_mode_still_asks_before_an_irreversible_command() {
        let (root, ws, app) = pair("auto-high");
        let c = ctx(&ws, "auto", vec![]);
        let mut asked = 0;
        // 让弹卡立刻被"拒绝"答复：测试里没有真人，走 abort 这条路把等待判掉
        let h = {
            let app = Arc::clone(&app);
            std::thread::spawn(move || {
                // 等卡出生再答，避免抢在注册之前
                for _ in 0..200 {
                    if app.approvals.size() > 0 {
                        app.approvals.abort("g");
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
            })
        };
        let why = guard(&app, &c, "shell", "git push --force origin main", "执行命令（bash）", &|| "".to_string(), false);
        h.join().unwrap();
        asked += 1;
        assert_eq!(why.unwrap(), "用户拒绝了这次「执行命令（bash）」。不要原样重试，换个方案或用 ask_user 问清楚。");
        assert_eq!(app.take_note("g"), "已拒绝", "结论要挂在紧接着那条工具消息上");
        assert_eq!(asked, 1);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn auto_mode_does_not_interrupt_for_a_low_risk_write() {
        let (root, ws, app) = pair("auto-low");
        let c = ctx(&ws, "auto", vec![]);
        let why = guard(&app, &c, "write", "src/new.md", "写入文件 src/new.md", &|| "新建".to_string(), true);
        assert_eq!(why, None, "低危不该打扰人：{why:?}");
        assert_eq!(app.approvals.size(), 0);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn an_allow_rule_skips_the_prompt_even_in_ask_mode() {
        let (root, ws, app) = pair("allow");
        app.policies_with(|s| s.add(&ws, Rule { tool: "write".into(), pattern: "*".into(), decision: Decision::Allow }));
        let c = ctx(&ws, "ask", vec![]);
        assert_eq!(guard(&app, &c, "write", "a.md", "写入文件 a.md", &|| "1 字符".to_string(), true), None);
        assert_eq!(app.approvals.size(), 0, "allow 规则命中后还在问人");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_deny_rule_blocks_with_the_reason_the_model_can_act_on() {
        let (root, ws, app) = pair("deny");
        app.policies_with(|s| s.add(&ws, Rule { tool: "write".into(), pattern: "secret.env".into(), decision: Decision::Deny }));
        let c = ctx(&ws, "auto", vec![]);
        let why = guard(&app, &c, "write", "secret.env", "写入 secret.env", &|| "x".to_string(), true).expect("deny 规则没生效");
        assert!(why.contains("规则拒绝") && why.contains("secret.env"), "{why}");
        assert_eq!(app.approvals.size(), 0, "被规则拒了还去问人");
        let _ = fs::remove_dir_all(&root);
    }

    /// plan 是最高优先级：**规则表也放行不了写**。
    #[test]
    fn plan_mode_beats_even_an_allow_rule() {
        let (root, ws, app) = pair("plan");
        app.policies_with(|s| s.add(&ws, Rule { tool: "write".into(), pattern: "*".into(), decision: Decision::Allow }));
        let c = ctx(&ws, "plan", vec![]);
        let why = guard(&app, &c, "write", "a.md", "写入文件 a.md", &|| "x".to_string(), true).expect("计划模式竟然被规则绕过了");
        assert!(why.contains("计划模式"), "{why}");
        let _ = fs::remove_dir_all(&root);
    }

    /// auto 档往工作区外写：当场拒，而不是弹一张没人看的卡等五分钟。
    /// 顺序必须是"结论 → 怎么办 → 才是要动哪个文件"，界面上只看得见前 90 个字符。
    #[test]
    fn auto_mode_refuses_a_write_outside_the_workspace_on_the_spot() {
        let (root, ws, app) = pair("outside");
        let c = ctx(&ws, "auto", vec![]);
        let why = guard(&app, &c, "write", "Q:\\somewhere\\a.md", "写入文件", &|| "x".to_string(), true).expect("该当场拒");
        assert!(why.starts_with("已拒绝：这条要写到工作区之外"), "{why}");
        assert!(why.contains("outside_write"), "要说不清怎么显式允许，模型只会换个写法再试：{why}");
        assert!(why.contains("Q:\\somewhere\\a.md"));
        assert_eq!(app.approvals.size(), 0, "当场拒不该变成弹卡");
        // 旗标开了就放（只摘掉"在外面"这一项）
        let c2 = ctx(&ws, "auto", vec![("outside_write".to_string(), true)]);
        assert_eq!(guard(&app, &c2, "write", "Q:\\somewhere\\a.md", "写入文件", &|| "x".to_string(), true), None);
        // 但同一条命令里的强推照样拦
        let c3 = ctx(&ws, "auto", vec![("outside_write".to_string(), true)]);
        let h = {
            let app = Arc::clone(&app);
            std::thread::spawn(move || {
                for _ in 0..200 {
                    if app.approvals.size() > 0 {
                        app.approvals.abort("g");
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
            })
        };
        guard(&app, &c3, "shell", "git push --force", "执行命令", &|| "".to_string(), false);
        h.join().unwrap();
        let _ = fs::remove_dir_all(&root);
    }

    /// 规则表说"这条要问我"（ASK）时，auto 也不许跳 —— 那是用户专门写下的。
    #[test]
    fn an_ask_rule_survives_auto_mode() {
        let (root, ws, app) = pair("ask-rule");
        app.policies_with(|s| s.add(&ws, Rule { tool: "write".into(), pattern: "careful*".into(), decision: Decision::Ask }));
        let c = ctx(&ws, "auto", vec![]);
        let h = {
            let app = Arc::clone(&app);
            std::thread::spawn(move || {
                for _ in 0..200 {
                    if app.approvals.size() > 0 {
                        app.approvals.abort("g");
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
            })
        };
        let why = guard(&app, &c, "write", "careful.md", "写入 careful.md", &|| "x".to_string(), true);
        h.join().unwrap();
        assert!(why.is_some(), "ASK 规则在 auto 档被跳过了");
        let _ = fs::remove_dir_all(&root);
    }

    /// 审批卡要摆出"能记住的这条规则"长什么样 —— 用户点"以后这类都允许"落的就是这个。
    #[test]
    fn the_approval_card_offers_the_rule_it_can_remember() {
        let (root, ws, app) = pair("pattern");
        let c = ctx(&ws, "auto", vec![]);
        let payload = std::sync::Arc::new(Mutex::new(String::new()));
        {
            let p = std::sync::Arc::clone(&payload);
            let app2 = Arc::clone(&app);
            std::thread::scope(|sc| {
                sc.spawn(|| {
                    let c2 = Ctx::for_run(&ws.join("home"), ws.clone(), vec![], "auto", "g", "r1");
                    guard(&app2, &c2, "shell", "rm -rf build", "执行命令（bash）", &|| "rm -rf build".to_string(), false)
                });
                // 卡一出生就把载荷抄下来并答"允许一次"
                for _ in 0..200 {
                    let rows = app.approvals.rows();
                    if let Some((id, w)) = rows.first() {
                        *p.lock().unwrap() = w.payload.clone();
                        app.approvals.complete(id, "allow_once");
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(10));
                }
            });
        }
        let j: serde_json::Value = serde_json::from_str(&payload.lock().unwrap().clone()).unwrap();
        // 词典里没给 `rm -rf` 单列条目，归约到 `rm` —— 记住的规则与拦下的判据是同一条
        assert_eq!(j["tool"], "shell");
        assert_eq!(j["pattern"], "rm");
        assert_eq!(j["risk"], "high");
        assert_eq!(app.take_note("g"), "允许一次");
        assert_eq!(guard(&app, &c, "write", "a.md", "写", &|| "".to_string(), true), None);
        let _ = fs::remove_dir_all(&root);
    }

    /// "本任务都允许"只把**这一条会话**切成 auto，不该把别的会话也切过去。
    #[test]
    fn allow_session_switches_only_that_session() {
        let (root, _ws, app) = pair("session");
        app.set_mode("g", "auto");
        assert_eq!(app.mode_of("g", "ask"), "auto");
        assert_eq!(app.mode_of("other", "ask"), "ask", "另一条会话不该跟着变");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn experiment_flags_hide_tools_from_the_model_entirely() {
        let f: Vec<(String, bool)> = vec![];
        assert!(!visible("browser", &f), "默认关着时 browser 对模型不存在");
        assert!(!visible("screen", &f));
        assert!(visible("read", &f));
        assert!(visible("browser", &[("browser_control".to_string(), true)]));
        // gated 是给界面的另一种说法：不是被关掉，是被实验开关挡着
        assert!(gated("browser", &f));
        assert!(!gated("read", &f));
        assert!(!gated("browser", &[("browser_control".to_string(), true)]));
    }
}
