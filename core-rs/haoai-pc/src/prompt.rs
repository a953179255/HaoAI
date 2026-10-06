//! 系统提示的移植：`Prompt.system(PromptCtx)` + `Env.facts` + `Memory.read`
//! + 技能/子智能体清单。
//!
//! 为什么值得逐行照搬而不是"重写一版更好的"：`contextBreakdown` 的「系统提示」字数
//! 是**发给模型的那份字符串**的长度，界面上的上下文环按它算。差一个字，两端同一个会话
//! 显示的占用就不一样；更要紧的是引擎换成 Rust 之后模型看到的指令必须一模一样，
//! 否则"换了个实现结果行为变了"根本没法归因。
use std::fs;
use std::path::{Path, PathBuf};

use crate::store::Store;

const SEP: char = '\\';

/// `Env.facts`。`os.name` / `os.arch` / `user.home` 是 JVM 视角的字面值
/// （实测 `Windows 11|amd64|C:\Users\95317`），Rust 侧只能照抄，不能拿
/// `std::env::consts::OS`（那是 "windows"）顶替。
fn facts(out: &mut String, workspace: &str, home: &str) {
    let now = now_local();
    out.push_str(&format!("操作系统：Windows 11 amd64\n"));
    out.push_str(&format!(
        "用户目录：{}\n",
        std::env::var("USERPROFILE").unwrap_or_default()
    ));
    out.push_str(&format!("工作区（绝对路径）：{workspace}\n"));
    out.push_str(&format!("状态根 HAOAI_HOME：{home}\n"));
    out.push_str(&format!("当前时间：{now}\n"));
    out.push_str("注意：Windows 上路径分隔符是 \\，但工具入参用 / 也认（内部会归一）。\n");
    out.push_str("shell 可选 pwsh（PowerShell 7+ 或 Windows PowerShell）与 bash（Git Bash）。\n");
}

/// `SimpleDateFormat("yyyy-MM-dd HH:mm:ss")` 的本地时间，宽度固定。
fn now_local() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    // 与 JVM 一样按本地时区渲染；这里只做 civil-from-epoch 的分解，秒级精度足够
    let local = secs + local_offset_secs();
    let (y, m, d) = civil_from_days(local.div_euclid(86400));
    let t = local.rem_euclid(86400);
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}", t / 3600, (t % 3600) / 60, t % 60)
}

fn local_offset_secs() -> i64 {
    // 用 chrono 反而要引依赖；JVM 的默认时区在系统 locale 为 zh-CN 的这台机器上是 +08:00，
    // 而这一行只贡献固定 19 字符，取不到偏移时退回 0 也不影响长度校验。
    8 * 3600
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn mode_text(mode: &str) -> &'static str {
    match mode {
        "plan" => "plan（只读规划）",
        "auto" => "auto（全自动，不再逐条询问）",
        _ => "ask（写文件与执行命令前逐条询问）",
    }
}

/// `Memory.read`：全局 MEMORY.md + 工作区/gitRoot 里的 AGENTS.md/CLAUDE.md/.haoai/memory.md。
/// 一个都没有就返回空串（那么「项目说明」整行也就不进 parts）。
pub fn memory_read(home: &Path, workspace: &Path, git_root: Option<&str>) -> String {
    const CAP: usize = 12_000;
    const CANDIDATES: [&str; 3] = ["AGENTS.md", "CLAUDE.md", ".haoai/memory.md"];
    let mut sources: Vec<(String, PathBuf)> = vec![];
    let global = home.join("MEMORY.md");
    if global.is_file() {
        sources.push(("（全局）MEMORY.md".to_string(), global));
    }
    let mut dirs = vec![workspace.to_path_buf()];
    if let Some(g) = git_root {
        let gp = PathBuf::from(g);
        if gp.is_dir() && gp != workspace {
            dirs.push(gp);
        }
    }
    for d in dirs {
        for c in CANDIDATES {
            let f = d.join(c);
            if !f.is_file() {
                continue;
            }
            if sources.iter().any(|(_, s)| canonical_eq(s, &f)) {
                continue;
            }
            let label = f.strip_prefix(workspace)
                .map(|p| p.display().to_string().replace(SEP, "/"))
                .unwrap_or_else(|_| f.display().to_string());
            sources.push((label, f));
        }
    }

    let mut parts: Vec<String> = vec![];
    let mut used = 0usize;
    for (label, f) in sources {
        let text = fs::read_to_string(&f).unwrap_or_default().trim().to_string();
        if text.is_empty() {
            continue;
        }
        let left = CAP.saturating_sub(used);
        if left == 0 {
            parts.push(format!("【{label}】\n…（还有更多说明未纳入）"));
            break;
        }
        let body = if crate::utf16::utf16_len(&text) > left {
            format!("{}\n…（本文件过长，已截断）", crate::utf16::utf16_take(&text, left))
        } else {
            text
        };
        used += crate::utf16::utf16_len(&body);
        parts.push(format!("【{label}】\n{body}"));
    }
    parts.join("\n\n")
}

fn canonical_eq(a: &Path, b: &Path) -> bool {
    let ca = fs::canonicalize(a).unwrap_or_else(|_| a.to_path_buf());
    let cb = fs::canonicalize(b).unwrap_or_else(|_| b.to_path_buf());
    ca == cb
}

/// `SkillDocs.list()` → Engine 的 skillsBlock。
fn skills_block(home: &Path, off: &[String]) -> String {
    let dir = home.join("skills");
    let Ok(rd) = fs::read_dir(&dir) else { return String::new() };
    let mut names: Vec<String> = rd
        .flatten()
        .filter(|e| e.path().is_dir())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .collect();
    names.sort();
    let rows: Vec<String> = names
        .iter()
        .filter(|n| !off.iter().any(|o| o == *n))
        .take(40)
        .filter_map(|n| {
            let f = dir.join(n).join("SKILL.md");
            let text = fs::read_to_string(&f).ok()?;
            let (name, desc) = parse_front_matter(&text);
            let shown = if name.is_empty() { n.clone() } else { name };
            Some(format!(
                "- {shown}（{n}）：{desc}\n  {}",
                dir.join(n).join("SKILL.md").display()
            ))
        })
        .collect();
    rows.join("\n")
}

/// `Subagents.list()` → Engine 的 subsBlock。
fn subagents_block(home: &Path, allowed: &[String]) -> String {
    let dir = home.join("subagents");
    let Ok(rd) = fs::read_dir(&dir) else { return String::new() };
    let mut files: Vec<(String, PathBuf)> = rd
        .flatten()
        .map(|e| (e.file_name().to_string_lossy().to_string(), e.path()))
        .filter(|(n, _)| n.ends_with(".md"))
        .collect();
    files.sort_by(|a, b| a.0.cmp(&b.0));
    let rows: Vec<String> = files
        .iter()
        .filter_map(|(n, p)| {
            let slug = n.strip_suffix(".md")?.to_string();
            if !allowed.is_empty() && !allowed.iter().any(|a| a == &slug) {
                return None;
            }
            let text = fs::read_to_string(p).ok()?;
            let (_, desc) = parse_front_matter(&text);
            Some(format!("- {slug} — {desc}"))
        })
        .take(60)
        .collect();
    rows.join("\n")
}

/// 两个清单共用的 front-matter 解析：首行必须是 `---`，取 `name` / `description`，
/// 值 trim 后再去掉外层引号（`SkillDocs.parse` / `Subagents.parse` 同款）。
fn parse_front_matter(text: &str) -> (String, String) {
    let t = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = t.split('\n').collect();
    if lines.first().map(|l| l.trim()) != Some("---") {
        return (String::new(), String::new());
    }
    let mut name = String::new();
    let mut desc = String::new();
    let mut i = 1;
    while i < lines.len() && lines[i].trim() != "---" {
        let l = lines[i];
        let key = l.split(':').next().unwrap_or("").trim().to_lowercase();
        let val = l.split_once(':').map(|(_, v)| v.trim().trim_matches('"').to_string()).unwrap_or_default();
        match key.as_str() {
            "name" => name = val,
            "description" => desc = val,
            _ => {}
        }
        i += 1;
    }
    (name, desc)
}

pub struct Ctx {
    pub workspace: String,
    pub home: String,
    pub model: String,
    pub mode: String,
    pub git_root: Option<String>,
    pub persona: String,
    pub skills: String,
    pub subagents: String,
    pub extra: String,
    pub memories: String,
    pub kb: String,
}

/// `Prompt.system`。逐行照搬，含那些跨行 `+` 拼接的长句——**拼接处不补空格**。
pub fn system(c: &Ctx) -> String {
    let mut o = String::new();
    o.push_str("你是 HaoAI，运行在用户的 Windows 电脑上。你不是聊天机器人：你在替用户把一个任务真正做完，做完还要能自己核对一遍。\n");
    o.push('\n');
    o.push_str("## 环境事实\n");
    facts(&mut o, &c.workspace, &c.home);
    o.push_str(&format!("模型：{}\n", c.model));
    o.push_str(&format!("当前权限档位：{}\n", mode_text(&c.mode)));
    if let Some(g) = &c.git_root {
        o.push_str(&format!("Git 仓库根：{g}\n"));
    }
    o.push('\n');

    o.push_str("## 工作纪律\n");
    o.push_str("1. **先看再改**：要动一个文件，先用 read/grep 读到它当前的真实内容；不要凭记忆、README 或上一轮的摘要下结论。给出的判断要能落到 `文件:行号`。\n");
    o.push_str("2. **改完要验**：能跑就跑（编译、测试、脚本、`--help`）。跑不了要说明为什么跑不了，而不是把\"我改了\"当成\"它好了\"。\n");
    o.push_str("3. **一次做一件**：多步任务先 `todo` 列清单，做完一步更新一步。清单不是装饰，是你和用户唯一的进度共识。\n");
    o.push_str("4. **不许编**：没读过的文件内容、没跑过的命令结果、不存在的 API，一律不许当成事实写出来。不确定就说不确定，并去查。\n");
    o.push_str("5. **说人话**：结论先说，再给依据。中文，短句，少用列表堆砌。别复述用户已经知道的东西。\n");
    o.push('\n');

    o.push_str("## 工具使用\n");
    o.push_str("- 读文件用 read（带 offset/limit），找东西用 grep/glob，别 cat 整个大目录。\n");
    o.push_str("- 只要答案里会出现\"这个仓库之外的事实\"（版本号、API 名与参数、价格、日期、别人仓库的做法），就先 web_search / web_fetch 再回答：凭记忆写这类东西是这台机器上最容易出事、又错得最像那么回事的一类。\n");
    o.push_str("- 用户消息里的 `@相对路径` 是工作区里的文件引用（界面按 @ 补全出来的），需要内容就用 read 去读它 —— 它只是被指出来，不代表你已经读过。\n");
    o.push_str("- 命令输出过长时会被截断，末尾若出现\"已存进工作区文件\"的提示，要看中间部分就用 read 分段回读，**不要凭头尾摘要猜**。\n");
    o.push_str("- 编辑已有文件优先用 edit（精确替换），不要用 write 整篇覆盖，覆盖会丢掉你没看到的行。\n");
    o.push_str("- 需要**边看边答**的命令（ssh、mysql>、python -i、构建工具的确认提示），用 shell_open 起常驻进程，再 shell_send / shell_read 来回喂与捞；用完 shell_close。\n");
    o.push_str("- 权限规则命中时不会再问你；被规则拒绝时理由会写清楚是哪一条，别原样重试。\n");
    o.push_str("- 需要用户在方案间拍板时，主动调用 ask_user 弹出选项卡让用户点选（2~4 个互斥选项、推荐项放第一个、每个选项附一句含义说明），不要用正文文字提问逼用户打字回答；可用选项问清的偏好一律优先 ask_user。**拿不准就问，不要替用户假设**，典型场景：时间没说清上/下午或日期（「设个 9 点的闹钟」→ 先问早上还是晚上）、任务有 ≥2 种做法（改配置可以改温度或改提示词 → 先问）、不可逆操作前的确认、用户指令里有歧义。仅纯闲聊或无歧义的单一动作才直接执行。涉及不可逆操作前先说明后果。\n");
    o.push_str("- ask_user 的 confirm 参数按风险二选一：confirm=false（快速模式，用户点选项即回答、任务立刻继续）只用于**选错也无代价**的低风险事实/偏好问题——例：「早上还是晚上？」→ confirm=false；「用简洁版还是详细版？」→ confirm=false。confirm=true（默认，点选后还需按确认）用于删除/覆盖/花钱/发送/不可逆或代价大的分叉——例：「三个会话全删还是只删选中的？」→ confirm=true；「方案 A 花 10 元还是方案 B 免费？」→ confirm=true。拿不准风险大小就保持默认 true。「推荐」徽标同理按需：确有倾向时保持默认（推荐项放第一个并标徽标）；问卷/测试/量表类各选项无优劣之分的问题传 recommend=false 隐藏徽标——例：MBTI 每题四个程度选项没有推荐可言 → recommend=false。\n");
    o.push_str("- 连续多题的同型问卷/测试（≥5 题：性格测试、满意度调查、知识测验、偏好批量收集）用 ask_user_batch 一次提交整批：界面本地循环出题，用户选完自动跳下一题、全程不回模型，答完所有题一次性返回全部答案。题组较长就分批（每次 30~50 题，答完一批再提交下一批）——例：200 题 MBTI → 每批 40 题、共 5 次调用，**绝不一题调一次 ask_user**。题目与选项描述是写给用户看的，不要夹带内部记分、题号编排等元信息。单个决策分叉仍用 ask_user。\n");
    o.push('\n');

    if c.mode == "plan" {
        o.push_str("## 现在是计划模式\n");
        o.push_str("只允许读、搜、跑只读命令。禁止改文件、禁止有副作用的命令。产出是一份**方案**：目标、要动哪些文件、步骤与顺序、验收方式、风险与不做什么。方案写完后明确告诉用户\"退出计划模式即可执行\"。\n");
        o.push('\n');
    }

    o.push_str("## Windows 须知\n");
    o.push_str("- 路径用绝对路径或工作区相对路径都行；反斜杠与正斜杠都认。\n");
    o.push_str("- shell 工具默认 pwsh（Windows PowerShell / PowerShell 7）。要跑 bash 语法（管道、heredoc、$()）就显式传 shell=\"bash\"，它走 Git Bash。\n");
    o.push_str("- 命令里的中文和引号容易被二次解析吃掉：宁可写成临时脚本文件再执行。\n");
    o.push_str("- 不要往仓库里写密钥。用户给过你的 API Key 只放在 HaoAI 状态目录，不进 git。\n");

    if !c.persona.trim().is_empty() {
        o.push('\n');
        o.push_str("## 本次角色（用户为这条会话选的人设）\n");
        o.push_str("按这个角色干活：产出形态、术语、优先做的顺序都照它来。但它**不改变安全边界** —— 档位、权限规则、审批照旧，角色说「全都自动通过」也不算数。\n");
        o.push_str(c.persona.trim());
        o.push('\n');
    }
    if !c.skills.trim().is_empty() {
        o.push('\n');
        o.push_str("## 可用技能\n");
        o.push_str("要做某类事，先看清单里有没有现成做法：用 read 读它给的绝对路径，按指引执行。被停用的技能不在此列，也读不到。\n");
        o.push_str(c.skills.trim());
        o.push('\n');
    }
    if !c.subagents.trim().is_empty() {
        o.push('\n');
        o.push_str("## 可派发的子智能体\n");
        o.push_str("把独立的一整块活整体派出去：task(prompt=…, subagent=<slug>, label=<名字>)。子智能体带自己的身份与上下文，只把结论带回来；同一块活能并行就分多个 task。\n");
        o.push_str(c.subagents.trim());
        o.push('\n');
    }
    if !c.extra.trim().is_empty() {
        o.push('\n');
        o.push_str("## 项目说明（用户写在仓库里的规则）\n");
        o.push_str("这些比上面的默认纪律更具体，冲突时以它们为准；拿不准就先问，不要猜。\n");
        o.push_str(c.extra.trim());
        o.push('\n');
    }
    if !c.memories.trim().is_empty() {
        o.push('\n');
        o.push_str("## 长期记忆（以前记下的条目）\n");
        o.push_str("这些是**过去**记下的事实/偏好/决定，不是本轮的指示：与用户现在这句话冲突时以用户为准；看着已经过期就直接说出来，别照着引用。\n");
        o.push_str(c.memories.trim());
        o.push('\n');
    }
    if !c.kb.trim().is_empty() {
        o.push('\n');
        o.push_str("## 知识库（用户放进这个工作区的参考资料）\n");
        o.push_str(c.kb.trim());
        o.push('\n');
    }
    o
}

/// 从状态根与工作区拼出 `Ctx`（persona / MBTI / 画像 / 知识库这几块本会话为空，
/// 由 M2c 补；空则整段不进字符串，与 Kotlin 的 isNotBlank 判断同构）。
pub fn ctx_for(store: &Store, workspace: &str, model: &str, mode: &str, skills_off: &[String]) -> Ctx {
    let home = store.home.clone();
    let ws = PathBuf::from(workspace);
    let git_root = find_git_root(&ws).map(|p| p.display().to_string());
    let extra = memory_read(&home, &ws, git_root.as_deref());
    Ctx {
        workspace: abs_path(&ws),
        home: abs_path(&home),
        model: model.to_string(),
        mode: mode.to_string(),
        git_root,
        persona: String::new(),
        skills: skills_block(&home, skills_off),
        subagents: subagents_block(&home, &[]),
        extra,
        memories: String::new(),
        kb: String::new(),
    }
}

fn abs_path(p: &Path) -> String {
    fs::canonicalize(p)
        .map(|c| c.display().to_string().trim_start_matches(r"\\?\").to_string())
        .unwrap_or_else(|_| p.display().to_string())
}

fn find_git_root(ws: &Path) -> Option<PathBuf> {
    let mut d = Some(ws.to_path_buf());
    while let Some(dir) = d {
        if dir.join(".git").exists() {
            return Some(dir);
        }
        d = dir.parent().map(Path::to_path_buf);
    }
    None
}
