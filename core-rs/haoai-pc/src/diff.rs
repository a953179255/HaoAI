//! 行级 diff（`pc/.../Tools.kt` 的 `object Diff`）。给审批卡与工具卡看的那份。
//!
//! 为什么不是 `git diff`：工作区不一定是仓库（新建的文件 git 根本不认），
//! 而且这一份要在工具结果里回给模型，必须自带边界（最多 80 行）不能无限长。
//!
//! **只复刻了线性近似那一条路径**（公共前后缀之外整段算一坨）。Kotlin 还有一条
//! 先试 `Hunks.of(old,new)`、切得出 ≥2 块就按块报数的分支，那条要连着逐块勾选的
//! 审批卡一起搬（`HunkPlan`/`hunkKeep`/`staleBaseline`），留在后面的整步。
//! 所以现在"一处文章改三行"在 Rust 侧会显示成 `−15 行 / +15 行` 而不是三个块 ——
//! 这正是 Kotlin 切不出块时的兜底形态，不是新造的错误。
/// Kotlin `CharSequence.lines()` 的口径：换行符认 `\r\n` / `\n` / `\r` 三种，
/// 结尾的换行**不**产生空行，而空串产生**一个**空行。
///
/// 后两条规则 Rust 的 `str::lines()` 都不满足（`"".lines()` 是空迭代器，且不认裸 `\r`），
/// 直接用它会让"新建文件"与"覆盖空文件"这两种情况报出行数差一位。
pub fn kotlin_lines(s: &str) -> Vec<&str> {
    let mut out: Vec<&str> = Vec::new();
    let b = s.as_bytes();
    let (mut start, mut i) = (0usize, 0usize);
    while i < b.len() {
        if b[i] == b'\r' || b[i] == b'\n' {
            out.push(&s[start..i]);
            i += if b[i] == b'\r' && b.get(i + 1) == Some(&b'\n') { 2 } else { 1 };
            start = i;
        } else {
            i += 1;
        }
    }
    if start < s.len() {
        out.push(&s[start..]);
    }
    if out.is_empty() {
        out.push("");
    }
    out
}

/// 统一风格的行级 diff。`max_lines` 是"这一坨最多显示几行"（Kotlin 默认 80）。
pub fn unified(path: &str, old: &str, new: &str, max_lines: usize) -> String {
    let a = kotlin_lines(old);
    let b = kotlin_lines(new);
    // 公共前缀
    let mut p = 0;
    while p < a.len() && p < b.len() && a[p] == b[p] {
        p += 1;
    }
    // 公共后缀（不能越过已匹配的前缀）
    let mut s = 0;
    while s < a.len() - p && s < b.len() - p && a[a.len() - 1 - s] == b[b.len() - 1 - s] {
        s += 1;
    }
    let removed = &a[p..a.len() - s];
    let added = &b[p..b.len() - s];
    if removed.is_empty() && added.is_empty() {
        return "−0 行 / +0 行（内容没变）".to_string();
    }
    // 前文取公共前缀的最后 3 行，后文取公共后缀的最后 3 行（前后内容这段一样，取哪边都行）
    let ctx_before = &a[p.saturating_sub(3)..p];
    let ctx_after = &a[a.len() - s.min(3)..a.len()];
    let mut body = String::new();
    for l in ctx_before {
        body.push_str(&format!(" {l}\n"));
    }
    for l in removed.iter().take(max_lines) {
        body.push_str(&format!("-{l}\n"));
    }
    if removed.len() > max_lines {
        body.push_str(&format!("-…（另有 {} 行未显示）\n", removed.len() - max_lines));
    }
    for l in added.iter().take(max_lines) {
        body.push_str(&format!("+{l}\n"));
    }
    if added.len() > max_lines {
        body.push_str(&format!("+…（另有 {} 行未显示）\n", added.len() - max_lines));
    }
    for l in ctx_after {
        body.push_str(&format!(" {l}\n"));
    }
    format!("−{} 行 / +{} 行   {path}\n{body}", removed.len(), added.len())
}

/// 旧接口：只给模型看的那一行摘要（工具结果会进历史，越短越好）。
pub fn stat(old: &str, new: &str) -> String {
    let a = kotlin_lines(old);
    let b = kotlin_lines(new);
    let mut p = 0;
    while p < a.len() && p < b.len() && a[p] == b[p] {
        p += 1;
    }
    let mut s = 0;
    while s < a.len() - p && s < b.len() - p && a[a.len() - 1 - s] == b[b.len() - 1 - s] {
        s += 1;
    }
    format!("−{} 行 / +{} 行", a.len() - p - s, b.len() - p - s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_splitting_matches_kotlin_on_the_two_edge_forms() {
        assert_eq!(kotlin_lines("a\nb\n"), vec!["a", "b"]);
        assert_eq!(kotlin_lines("a\r\nb"), vec!["a", "b"]);
        assert_eq!(kotlin_lines("a\rb"), vec!["a", "b"]);
        assert_eq!(kotlin_lines(""), vec![""]);
        assert_eq!(kotlin_lines("\n"), vec![""]);
        assert_eq!(kotlin_lines("a\n\nb"), vec!["a", "", "b"]);
    }

    #[test]
    fn diff_reports_the_block_with_three_lines_of_context() {
        let old = "1\n2\n3\n4\n5\n6\n7\n";
        let new = "1\n2\n3\nX\n5\n6\n7\n";
        let d = unified("a.txt", old, new, 80);
        assert_eq!(
            d,
            "−1 行 / +1 行   a.txt\n 1\n 2\n 3\n-4\n+X\n 5\n 6\n 7\n",
            "整份形状都要对上 Kotlin：{d:?}"
        );
        assert_eq!(stat(old, new), "−1 行 / +1 行");
    }

    #[test]
    fn unchanged_content_says_so_instead_of_printing_an_empty_block() {
        assert_eq!(unified("a.txt", "same\n", "same\n", 80), "−0 行 / +0 行（内容没变）");
    }

    /// 新建一个文件时基线是空串。空串按 Kotlin 是"一行空"，所以报 −1/+N，
    /// 用 Rust 的 lines() 会少报一行 —— 这条断言钉的就是那个差一位。
    #[test]
    fn creating_a_file_from_empty_shows_the_blank_base_line() {
        let d = unified("new.md", "", "# 标题\n正文\n", 80);
        assert!(d.starts_with("−1 行 / +2 行   new.md\n"), "{d:?}");
    }

    #[test]
    fn long_blocks_are_cut_with_an_explicit_remainder() {
        let old = "keep\n";
        let new = "keep\n".to_string() + &"x\n".repeat(90);
        let d = unified("a.txt", old, &new, 80);
        assert!(d.contains("+…（另有 10 行未显示）"), "{}", d.lines().last().unwrap_or(""));
        assert!(d.contains("−0 行 / +90 行"), "{d}");
    }
}
