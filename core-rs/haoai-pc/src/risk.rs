//! 审批的风险分级：让"要不要人点头"和**后果的可逆性**挂钩。
//! 移植自 `pc/src/main/kotlin/com/haoai/pc/Risk.kt`，判据与文案逐条对齐。
//!
//! 为什么这套东西必须和写类工具一起搬：ask 档下每个 write/exec 都弹一张一样的卡，
//! 人点到第三次就开始无脑按"允许"，这时候弹框已经不提供任何保护 —— 而真正不可逆的
//! 那一下（`git push --force`、删工作区外的文件）混在里面，长得和改一行 README 完全一样。
//!
//! 判据全部是**看得见的字符串**（工具名、路径、命令行），不调模型：
//! 分级错了顶多是多问一次，但必须能解释、能被测试钉住。
//! **界面按 `code()`（low/mid/high）配色，不按中文文案匹配** —— 改文案会把徽标
//! 悄悄改成永远低危。
use std::cmp::Reverse;
use std::sync::OnceLock;

use crate::utf16::utf16_len;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Risk {
    Low,
    Mid,
    High,
}

pub struct Verdict {
    pub level: Risk,
    pub why: String,
}

impl Verdict {
    /// 给界面的稳定码 = Kotlin `level.name.lowercase(Locale.ROOT)`。
    pub fn code(&self) -> &'static str {
        match self.level {
            Risk::Low => "low",
            Risk::Mid => "mid",
            Risk::High => "high",
        }
    }
}

pub fn label(level: Risk) -> &'static str {
    match level {
        Risk::Low => "低危",
        Risk::Mid => "中危",
        Risk::High => "高危",
    }
}

/// Kotlin 的 `String.lowercase(Locale.ROOT)`：只折大小写、不碰别的。
/// 中文原样通过是**必须**的，所以不能用 `to_ascii_lowercase`（那是另一个语义）。
pub fn lower(s: &str) -> String {
    s.chars().flat_map(char::to_lowercase).collect()
}

/// Kotlin 的 `trim()`（`Char.isWhitespace()`）。Rust 的 `str::trim` 用的正是同一套
/// Unicode 空白判定（含 U+3000 全角空格），所以直接用它就是等价的。
pub fn trim(s: &str) -> &str {
    s.trim()
}

/// `cmd.replace("2>&1", " ").replace(">&2", " ")`：非正则，字面量子串，全部替换。
fn replace_all(s: &str, from: &str, to: &str) -> String {
    s.split(from).collect::<Vec<_>>().join(to)
}

/// Kotlin `split(Regex("\\s+"))` 的**分段语义**：连续空白算一个分隔符，
/// 分段数 = 分隔符数 + 1，所以 `"  ls"` 的首段是空串 —— 而 `readOnly` 判的正是
/// `tok[0]`，用 Rust 的 `split_whitespace()`（跳过空段）会把前导空白后的程序名认错。
/// 尾部空段按 Java `String.split` 的规矩一律剥掉（`"ls "` → `["ls"]`）。
pub fn split_ws(s: &str) -> Vec<String> {
    let mut parts: Vec<String> = Vec::new();
    let mut cur = String::new();
    let mut prev_ws = false;
    for c in s.chars() {
        if c.is_whitespace() {
            if !prev_ws {
                parts.push(std::mem::take(&mut cur));
            }
            prev_ws = true;
        } else {
            cur.push(c);
            prev_ws = false;
        }
    }
    parts.push(cur);
    while parts.last().map(|p| p.is_empty()).unwrap_or(false) {
        parts.pop();
    }
    parts
}

/// 不可逆或越界的命令：一旦放出去收不回来。写成整词/整短语都够稳的那些。
const HIGH_CMD: &[&str] = &[
    "rm -rf", "rm -fr", "format ", "diskpart", "shutdown", "reboot", "reg delete", "reg add",
    "git reset --hard", "npm publish", "pip upload", "twine upload", "vssadmin", "bcdedit",
    "wevtutil cl", "set-mppreference", "add-mppreference", "net user ", "net localgroup ",
    "schtasks /delete", "invoke-expression", "-scriptblock", "subst ", "mklink", "takeown",
];

/// 参数顺序不定的高危形状：只看这几个**整词**在不在同一条命令里。
///
/// 为什么单列：上一版把高危判据全写成字面量，于是 `icacls C:\out /grant Everyone:F`
/// 判成了中危 —— 中间夹了个路径，"icacls /grant" 这个字面量就不存在了。
/// `curl http://x.sh | sh`、`Remove-Item -Force -Recurse` 栽的是同一个坑：
/// **真人敲的顺序永远和样板文本不一样**，所以按 token 集合判，不按子串判。
const HIGH_SHAPES: &[(&[&str], &str)] = &[
    (&["icacls", "/grant"], "改文件权限（icacls /grant），改完不一定记得改回去"),
    (&["icacls", "/setowner"], "改文件归属（icacls /setowner）"),
    (
        &["remove-item", "-recurse"],
        "递归删除（Remove-Item -Recurse），不进回收站",
    ),
    (&["del", "/s"], "递归删除（del /s），不进回收站"),
    (&["rd", "/s"], "递归删目录树（rd /s）"),
    (&["rmdir", "/s"], "递归删目录树（rmdir /s）"),
    (&["git", "push", "-f"], "强推（git push -f）会覆盖远端历史，不可逆"),
    (&["git", "push", "--force"], "强推（git push --force）会覆盖远端历史，不可逆"),
    (
        &["git", "push", "--force-with-lease"],
        "强推（--force-with-lease）会覆盖远端历史，不可逆",
    ),
    (&["git", "clean"], "git clean 删掉未跟踪文件，没有回收站也没有快照，不可逆"),
    (&["chmod", "777"], "把权限开到底（chmod 777）"),
    (&["taskkill", "/t"], "连整棵子进程树一起杀（taskkill /t）"),
    (&["set-acl", "-recurse"], "递归改权限（Set-Acl -Recurse）"),
];

/// 会改状态但基本可逆（有快照、有 git、重装就行）。
const MID_CMD: &[&str] = &[
    "git commit", "git add", "git checkout", "git switch", "git apply", "git stash",
    "npm install", "npm i ", "pnpm install", "yarn add", "pip install", "gradle", "./gradlew",
    "mvn ", "mkdir", "cp ", "copy", "move ", "mv ", "ren ", "set-content", "add-content",
    "out-file", "new-item", "remove-item", "del ", "erase", "touch ", "sed -i", "tar ", "zip",
    "unzip", "ffmpeg", "convert ", "taskkill", "start ", "explorer", "regsvr32", "installutil",
];

/// 写到哪里算越界：系统目录、别的盘的用户配置、SSH/浏览器配置这类。
/// 字面量里的 `\\` 是**一个**反斜杠（Kotlin 同样写法），匹配前统一按小写比。
const SENSITIVE: &[&str] = &[
    "\\.ssh\\", "\\.gnupg\\", "\\.aws\\", "\\windows\\", "\\program files",
    "\\appdata\\roaming\\microsoft", "\\hosts", "\\system32", "\\.git\\hooks\\", "\\.qoder\\",
    "\\.android\\", "\\.gradle\\", "\\.bashrc", "\\.profile", "\\.zshrc", "\\.gitconfig",
    "\\.npmrc", "\\.netrc",
];

/// 只读程序名：整条命令每一段的首个程序都在这里，才算"看一眼而已"。
///
/// 为什么要这一档：以前 shell 兜底是中危，于是 auto 档下一个 `git status`
/// 也挂着"会改状态"的徽标 —— 分级一旦开始说谎，人就再也不看它了，
/// 而这套东西省下的正是"那一下要不要停"的信任。
/// 保守是对的：判错成中危只是徽标难看，判错成低危才会漏问。
const READ_ONLY_BIN: &[&str] = &[
    "ls", "dir", "cat", "type", "head", "tail", "wc", "sort", "uniq", "grep", "rg", "find", "fd",
    "tree", "stat", "file", "du", "df", "free", "uname", "whoami", "hostname", "date", "which",
    "where", "pwd", "echo", "printf", "env", "printenv", "ver", "systeminfo", "tasklist",
    "get-childitem", "get-content", "get-location", "get-item", "get-process", "select-string",
    "ipconfig", "ifconfig", "ping", "tracert", "nslookup",
];

const GIT_READ_ONLY: &[&str] = &[
    "status", "log", "diff", "show", "blame", "branch", "tag", "remote", "ls-files", "rev-parse",
    "describe", "shortlog", "config", "worktree", "grep",
];

/// 「下载下来直接喂给解释器」的词表，**顺序就是尝试顺序**（与正则的择一一致）。
const INTERPRETERS: &[&str] = &[
    "sh", "bash", "zsh", "dash", "pwsh", "powershell", "iex", "invoke-expression", "python",
    "python3", "node", "perl", "ruby",
];

/// `\b` 的 java.util.regex 口径：`[A-Za-z0-9_]`。
/// 这一条**必须自己定**：Rust 的 regex crate 用 Unicode 词义（汉字也算词字符），
/// 与 JVM 在同一份输入上可能给出不同判定。
fn is_word(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

/// 手写扫描 `PIPE_TO_INTERPRETER` 那条正则，而不用 regex crate。
///
/// 一个硬性理由：`invoke-expression` 结尾是 `n`、后面可能紧跟 `-`，`\b` 在
/// java 与 rust（Unicode 词义）两边规则不同；同一处判据两端分叉 = 同一个命令
/// 在一边高危、另一边中危，那是最难查的那种错。
/// 返回的是**整段匹配**（Kotlin 用 `match.value`，含前面的 `|` 与空白），已 trim。
fn pipe_match(cmd: &str) -> Option<String> {
    let cs: Vec<char> = cmd.chars().collect();
    let mut i = 0;
    while i < cs.len() {
        if cs[i] != '|' {
            i += 1;
            continue;
        }
        let mut j = i + 1;
        while j < cs.len() && cs[j].is_whitespace() {
            j += 1;
        }
        let rest: String = cs[j..].iter().collect();
        for w in INTERPRETERS {
            if !rest.starts_with(w) {
                continue;
            }
            let next = cs.get(j + w.chars().count());
            // `\b` 要求组后一个是非词字符或串尾
            if next.map(|c| !is_word(*c)).unwrap_or(true) {
                let end = j + w.chars().count();
                return Some(trim(&cs[i..end].iter().collect::<String>()).to_string());
            }
        }
        i += 1;
    }
    None
}

/// 一段一段看：任何一段有重定向、删除、执行外部程序，就不算只读。
pub fn read_only(cmd: &str) -> bool {
    let c = replace_all(&replace_all(cmd, "2>&1", " "), ">&2", " ");
    if c.contains('>') {
        return false;
    }
    let segs: Vec<&str> = c
        .split(['|', ';'])
        // Kotlin 一次给四个分隔符 `"|" ";" "&&" "||"`；`&&` 与 `||` 互不重叠，
        // 先按 `&&` 再按 `||` 切与一次切完等价，切出的空段下面统一 filter 掉。
        .flat_map(|s| s.split("&&").flat_map(|x| x.split("||")))
        .map(trim)
        .filter(|s| !s.is_empty())
        .collect();
    if segs.is_empty() {
        return false;
    }
    segs.iter().all(|s| {
        let tok = split_ws(s);
        let first = tok.first().map(|t| t.as_str()).unwrap_or("");
        if s.contains("-delete") || s.contains("-exec") || s.contains("--output") {
            false
        } else if first == "cd" {
            true
        } else if first == "git" {
            tok.get(1).map(|t| GIT_READ_ONLY.contains(&t.as_str())).unwrap_or(false)
        } else {
            READ_ONLY_BIN.contains(&first)
        }
    })
}

/// 整词匹配：按空白和命令分隔符切开，所以 `del` 不会被 `add` 里那两个字母骗到。
/// Kotlin 用 `split(Regex("[\\s;|&]+"))` —— 字符类里的 `\s` 含 `\r\n\t\v\f` 与 Unicode 空白。
fn has_tokens(cmd: &str, need: &[&str]) -> bool {
    let toks: std::collections::HashSet<String> =
        cmd.split(|c: char| c.is_whitespace() || ";|&".contains(c))
            .filter(|t| !t.is_empty())
            .map(|t| t.to_string())
            .collect();
    need.iter().all(|n| toks.iter().any(|t| t == n))
}

/// `RiskOf.of`。`outside_workspace` / `exists` 由调用方判 —— 它才知道该按哪个
/// 工作区解析这个相对路径。
pub fn of(tool: &str, subject: &str, detail: &str, outside_workspace: bool, exists: bool) -> Verdict {
    let cmd = if tool == "shell" || tool == "run_code" || tool == "exec" {
        lower(&format!("{subject} {detail}"))
    } else {
        String::new()
    };
    let path = if tool == "shell" { String::new() } else { lower(subject) };

    if let Some(hit) = HIGH_CMD.iter().find(|&&k| cmd.contains(k)) {
        return verdict(Risk::High, format!("命令里的「{}」不可逆", trim(hit)));
    }
    if let Some((_, why)) = HIGH_SHAPES.iter().find(|(t, _)| has_tokens(&cmd, t)) {
        return verdict(Risk::High, why.to_string());
    }
    if let Some(hit) = pipe_match(&cmd) {
        return verdict(
            Risk::High,
            format!("把下载来的内容直接喂给解释器（{}）", hit.trim()),
        );
    }
    if let Some(hit) = SENSITIVE.iter().find(|&&k| path.contains(&lower(k))) {
        return verdict(
            Risk::High,
            format!("要写的路径落在敏感位置（{}）", hit.trim_matches('\\')),
        );
    }
    if outside_workspace && tool != "shell" {
        /*
         * "在外面"要分两种，它们的后果不是一回事：
         * ① `write`/`edit` 写到外面 = 可能盖掉别人已有的文件，而撤销（/rewind）只在工作区里带得回来；
         * ② `media` 导产物到外面 = **新建一个文件**，谁也没动。素材库与成片目录在 D:\ 是常态，
         *    把它判成高危的代价是：定时任务与任务链都以 auto 档跑，auto 不跳高危 ⇒ 弹一张
         *    没人看的卡等 300 秒，然后模型收到"超时未答，按拒绝处理"。
         * 所以产物工具只在**目标已存在**（= 要覆盖）时才算高危，新建就是中危：
         * ask 档照样弹卡（人在电脑前，看一眼路径再点头是对的），auto 档直接过。
         */
        let product = tool == "media";
        if product && !exists {
            return verdict(Risk::Mid, "产物写到工作区之外（新建文件，不动已有的东西）");
        }
        return verdict(Risk::High, "这个路径在工作区之外，撤销时不会被带回来");
    }
    // 覆盖已存在的文件：有快照能回，但比新建要紧。
    if tool == "edit" || tool == "write" {
        return if exists {
            verdict(Risk::Mid, "要改一个已存在的文件（改前会留快照）")
        } else {
            verdict(Risk::Low, "新建文件，没动过别的东西")
        };
    }
    if !cmd.is_empty() {
        if read_only(&cmd) {
            return verdict(Risk::Low, "只是看一眼，不写不落盘");
        }
        if let Some(hit) = MID_CMD.iter().find(|&&k| cmd.contains(k)) {
            return verdict(Risk::Mid, format!("会改状态（{}），但可逆", trim(hit)));
        }
    }
    if tool == "record" || tool == "media" || tool == "shell" || tool == "run_code" {
        return verdict(Risk::Mid, "会起进程或写产出文件");
    }
    verdict(Risk::Low, "只读或只在本机内存里")
}

fn verdict(level: Risk, why: impl Into<String>) -> Verdict {
    Verdict { level, why: why.into() }
}

/**
 * 命令前缀归约词典：只放**真会改变系统或不可逆**的那些（opencode arity.ts 的子集）。
 * 没命中的命令退回"前两个 token"，够用且不会误伤：`ls -la /x` → `ls`。
 */
const ARITY: &[&str] = &[
    "git push", "git reset", "git clean", "git checkout", "git revert", "git rebase", "git rm",
    "git tag", "git filter-branch", "git config", "rm", "rmdir", "del", "erase", "move", "mv",
    "cp", "chmod", "chown", "takeown", "taskkill", "kill", "pkill", "shutdown",
    "restart-computer", "stop-computer", "reg", "regedit", "sc", "netsh", "net user",
    "net localgroup", "schtasks", "format", "diskpart", "vssadmin", "bcdedit", "cipher",
    "npm install", "npm uninstall", "npm publish", "pnpm add", "yarn add", "pip install",
    "pip uninstall", "uv pip install", "conda install", "conda remove", "dotnet tool",
    "winget install", "winget uninstall", "choco install", "choco uninstall", "gradle clean",
    "gradle publish", "cargo publish", "docker rm", "docker system prune", "docker rmi",
    "kubectl delete", "aws", "az", "gcloud", "ssh", "scp", "curl", "wget",
    "invoke-expression", "iex", "set-executionpolicy", "new-item", "remove-item",
    "set-itemproperty", "start-process", "start-job",
];

/// Kotlin `.sortedByDescending { it.length }` 是**稳定**排序（Rust 的 `sort_by_key` 也是），
/// 长度撞车的词条保持原顺序 ⇒ 两端 firstOrNull 选到的同一条。只排一次。
fn arity_sorted() -> &'static [&'static str] {
    static V: OnceLock<Vec<&'static str>> = OnceLock::new();
    V.get_or_init(|| {
        let mut v: Vec<&'static str> = ARITY.iter().copied().collect();
        // `it.length` 在 Kotlin 是 UTF-16 单元数，所以显式走 utf16_len 而不是 len()：
        // 词典今天全 ASCII，将来加中文词条不会静默变味。
        v.sort_by_key(|s| Reverse(utf16_len(s)));
        v
    })
}

pub fn command_prefix(cmd: &str) -> String {
    // Windows 的命令与别名大小写不敏感（Remove-Item / remove-item / RM 都算一条），
    // 归约前先统一小写，否则规则表形同虚设。
    let norm = lower(&cmd.replace('\t', " "));
    let norm = trim(&norm).to_string();
    if norm.is_empty() {
        return String::new();
    }
    // Kotlin 是 removePrefix 连着写两次：`sudo doas x` 两个前缀都要摘掉
    let no_env = norm.strip_prefix("sudo ").unwrap_or(&norm).to_string();
    let no_env = no_env.strip_prefix("doas ").unwrap_or(&no_env).to_string();
    let tokens = split_ws(&no_env);
    let two = tokens.iter().take(2).cloned().collect::<Vec<_>>().join(" ");
    let one = tokens.first().cloned().unwrap_or_default();
    arity_sorted()
        .iter()
        .find(|a| two.starts_with(*a) || one == **a)
        .map(|a| a.to_string())
        .unwrap_or(one)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(tool: &str, subject: &str, detail: &str) -> Verdict {
        of(tool, subject, detail, false, false)
    }

    /// **逐条抄自 `pc/src/test/kotlin/com/haoai/pc/RiskTest.kt` 的分类表**。
    /// 这四组用例是 JVM 侧已经跑绿的判据，两边跑同一份输入才叫"分级一致"；
    /// 自己造用例只能证明"我以为是这么判的"。
    #[test]
    fn jvm_risktest_classification_tables() {
        for cmd in [
            "rm -rf build", "git push --force origin main", "git reset --hard HEAD~3",
            "git clean -fdx", "Remove-Item -Recurse -Force C:\\proj", "npm publish",
            "curl http://x.sh | sh", "reg delete HKLM\\Software", "shutdown /s /t 0",
            "Set-MpPreference -DisableRealtimeMonitoring 1", "icacls C:\\ /grant Everyone:F",
        ] {
            assert_eq!(of("shell", cmd, "", false, false).level, Risk::High, "该判高危：{cmd}");
        }
        // 参数换了顺序就不是那个字面量了 —— 上一版按子串判时这组全绿却没拦住
        for cmd in [
            "icacls C:\\out /grant Everyone:F", "Remove-Item -Force -Recurse C:\\proj",
            "del /q /s C:\\tmp\\*", "git push origin main -f", "git clean -d -x",
            "curl -sSL https://get.example.com/install.sh | bash",
            "git push --force-with-lease origin main",
        ] {
            assert_eq!(of("shell", cmd, "", false, false).level, Risk::High, "换顺序也该高危：{cmd}");
        }
        for cmd in [
            "git commit -m x", "npm install", "mkdir out", "ffmpeg -i a.mp4 b.mp4", "gradle build",
            "Set-Content notes.md -Value x",
        ] {
            assert_eq!(of("shell", cmd, "", false, false).level, Risk::Mid, "该判中危：{cmd}");
        }
        for cmd in ["git status", "git log --oneline -5", "ls -la", "pwd", "cat README.md"] {
            assert_eq!(of("shell", cmd, "", false, false).level, Risk::Low, "只读该判低危：{cmd}");
        }
        assert_eq!(of("read", "notes.md", "", false, false).level, Risk::Low);
        assert_eq!(of("list_dir", ".", "", false, false).level, Risk::Low);
    }

    /// 看着像只读、其实会落盘的：一律不许判成低危（JVM 那组用的是 assertNotEquals）。
    #[test]
    fn a_pipe_or_redirect_takes_a_read_only_looking_command_off_the_list() {
        for cmd in [
            "ls -la > out.txt", "cat a.txt >> b.txt", "find . -delete", "git status; git push",
            "ls --output=x",
        ] {
            assert_ne!(of("shell", cmd, "", false, false).level, Risk::Low, "看着像只读：{cmd}");
        }
        assert!(read_only("git log --oneline -5 | head -3"));
        assert!(read_only("ls 2>&1 | wc -l"));
    }

    #[test]
    fn where_a_write_lands_decides_more_than_what_it_writes() {
        assert_eq!(of("write", "a.txt", "旧内容", false, true).level, Risk::Mid);
        assert_eq!(of("write", "brand-new.md", "", false, false).level, Risk::Low);
        assert_eq!(of("write", "../sibling.md", "", true, false).level, Risk::High);
        for path in [
            "C:\\Windows\\System32\\drivers\\etc\\hosts",
            "C:\\Users\\me\\.ssh\\config",
            "C:\\proj\\.git\\hooks\\pre-commit",
        ] {
            assert_eq!(of("write", path, "", false, false).level, Risk::High, "敏感位置：{path}");
        }
    }

    #[test]
    fn the_reason_is_a_sentence_a_human_can_act_on() {
        let v = of("shell", "git push --force origin main", "", false, false);
        assert!(v.why.contains("git push --force") && v.why.contains("不可逆"), "{}", v.why);
        assert_eq!(label(v.level), "高危");
        // 产物导出到中危那条，理由要同时说清"新建"和"不动"（JVM 那条断言原样搬）
        let w = of("media", "D:\\素材\\x.mp3", "", true, false);
        assert!(w.why.contains("新建") && w.why.contains("不动"), "{}", w.why);
    }

    #[test]
    fn command_prefix_reduces_to_the_rule_worthy_head() {
        // JVM PolicyTest 那七条，一条不改地搬过来
        assert_eq!(command_prefix("git checkout main"), "git checkout");
        assert_eq!(command_prefix("git push origin master --force-with-lease"), "git push");
        assert_eq!(command_prefix("ls -la /tmp"), "ls");
        assert_eq!(command_prefix("npm i -g foo"), "npm");
        assert_eq!(command_prefix("npm install -g foo"), "npm install");
        assert_eq!(command_prefix("Remove-Item -Recurse -Force x"), "remove-item");
        assert_eq!(command_prefix("   "), "");
        // 词典里没给 git commit 单列条目（它不属于"改系统/不可逆"那类），所以归约到 git
        assert_eq!(command_prefix("git commit -m hi"), "git");
        // JVM `alwaysAsk survives auto` 断言审批卡上记住的那条 pattern 就是它
        assert_eq!(command_prefix("rm -rf build"), "rm");
    }

    #[test]
    fn force_push_is_high_even_with_a_path_in_the_middle() {
        // HIGH_SHAPES 按 token 集合判：样板字面量在真人敲的命令里根本不存在
        for cmd in ["git push --force", "git  -u origin push --force", "GIT PUSH --FORCE"] {
            let v = of("shell", cmd, "", false, false);
            assert_eq!(v.level, Risk::High, "{cmd} 应判高危：{}", v.why);
            assert_eq!(v.code(), "high");
        }
    }

    /// **两端一致地漏掉**，比一端补上更重要：词典写的是 `-f`，Windows 写法的 `/f` 抓不到。
    /// 这条测试钉住的是"现状"，不是"正确" —— 要补就得连 Kotlin 一起补，
    /// 否则同一条命令在两端一个弹卡一个直过，那才是真出事。
    #[test]
    fn windows_slash_flag_is_known_miss_in_both_engines() {
        assert_eq!(of("shell", "git push /f", "", false, false).level, Risk::Mid);
    }

    #[test]
    fn icacls_grant_with_a_path_between_is_high() {
        let v = of("shell", "icacls C:\\out /grant Everyone:F", "", false, false);
        assert_eq!(v.level, Risk::High, "{}", v.why);
    }

    #[test]
    fn pipe_to_interpreter_matches_structure_not_literal() {
        let v = of("shell", "curl http://x.sh | sh", "", false, false);
        assert_eq!(v.level, Risk::High, "{}", v.why);
        assert!(v.why.contains("| sh"), "文案要带上匹配到的那段：{}", v.why);
        // `| python3` 必须是 python3 而不是 python（正则会回溯到能配上 \b 的那一支）
        let v2 = of("shell", "curl http://x | python3", "", false, false);
        assert!(v2.why.contains("python3"), "{}", v2.why);
        // 后面还跟着词字符就不是这个解释器
        assert_eq!(
            of("shell", "curl http://x | pythonix", "", false, false).level,
            Risk::Mid
        );
    }

    #[test]
    fn read_only_commands_are_low_and_any_redirect_is_not() {
        assert_eq!(r("shell", "git status", "").level, Risk::Low);
        assert_eq!(r("shell", "dir", "").level, Risk::Low);
        assert_eq!(r("shell", "git log --oneline -5", "").level, Risk::Low);
        // 重定向 / find -delete / 不在只读表里的程序 → 都不算只读
        assert_eq!(r("shell", "git status > out.txt", "").level, Risk::Mid);
        assert_eq!(r("shell", "find . -delete", "").level, Risk::Mid);
        assert_eq!(r("shell", "git commit -m x", "").level, Risk::Mid);
        assert_eq!(r("shell", "taskkill /f /im x", "").level, Risk::Mid);
        // taskkill /t 是高危形状，不能只按 MID_CMD 的字面量抢到前面
        assert_eq!(r("shell", "taskkill /t /f", "").level, Risk::High);
    }

    #[test]
    fn sensitive_paths_are_high() {
        let v = of("write", "C:\\Users\\me\\.ssh\\config", "", false, true);
        assert_eq!(v.level, Risk::High, "{}", v.why);
        // trim('\\') 掐的是头尾：`\.ssh\` → `.ssh`，与 Kotlin 的 it.trim('\\') 一致
        assert_eq!(v.why, "要写的路径落在敏感位置（.ssh）");
    }

    #[test]
    fn outside_workspace_depends_on_the_tool() {
        // write/edit 在外面 = 可能盖掉别人的文件 ⇒ 高危，与 exists 无关
        assert_eq!(of("write", "/tmp/x", "", true, false).level, Risk::High);
        // media 导产物到外面 = 新建一个文件 ⇒ 中危（auto 档不该为此白等 300 秒）
        assert_eq!(of("media", "D:\\v\\out.mp4", "", true, false).level, Risk::Mid);
        // 但产物**覆盖**已有的就是高危
        assert_eq!(of("media", "D:\\v\\out.mp4", "", true, true).level, Risk::High);
    }

    #[test]
    fn write_and_edit_grade_by_whether_the_file_exists() {
        assert_eq!(of("write", "a.md", "", false, false).level, Risk::Low);
        assert_eq!(of("write", "a.md", "", false, true).level, Risk::Mid);
        assert_eq!(of("edit", "a.md", "", false, true).level, Risk::Mid);
    }

    #[test]
    fn command_prefix_reduces_by_the_arity_dictionary() {
        assert_eq!(command_prefix("git checkout main"), "git checkout");
        assert_eq!(command_prefix("  Remove-Item  -Force "), "remove-item");
        assert_eq!(command_prefix("sudo git push origin"), "git push");
        assert_eq!(command_prefix("ls -la /x"), "ls");
        assert_eq!(command_prefix(""), "");
        // 词典是 UTF-16 长度降序 + 稳定序：两条同长时先写的赢
        assert_eq!(command_prefix("git push-with-x"), "git push");
    }

    #[test]
    fn split_ws_keeps_the_leading_empty_segment_java_does() {
        assert_eq!(split_ws("  ls -la"), vec!["".to_string(), "ls".to_string(), "-la".to_string()]);
        assert_eq!(split_ws("ls "), vec!["ls".to_string()]);
        assert_eq!(split_ws("a  b"), vec!["a".to_string(), "b".to_string()]);
        assert!(split_ws(" ").is_empty());
    }
}
