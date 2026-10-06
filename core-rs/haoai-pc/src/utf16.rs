/// Kotlin/JVM 的 `String.take(n)` 按 **UTF-16 code unit** 计数，而 Rust 的 `len()` 是 UTF-8
/// 字节、`chars()` 是码点。汉字两者相同（BMP 内 1 字符 = 1 单元），但 emoji 等增补平面字符
/// 在 UTF-16 里占 2 个单元、在 Rust 里只算 1 个 char —— 截点就会差一位。
///
/// `Server.stateJson` 的 32 条工具描述里有 17 条会被 `desc.take(90)` 截到，用的就是这个函数；
/// `golden.rs` 的差分测试把它和 JVM 的实际输出钉在一起。
pub fn utf16_len(s: &str) -> usize {
    s.chars().map(|c| if c as u32 > 0xFFFF { 2 } else { 1 }).sum()
}

/// 等价于 Kotlin 的 `s.take(n)`：按 UTF-16 单元数取前缀，返回原始切片（不重新分配）。
pub fn utf16_take(s: &str, n: usize) -> &str {
    let mut units = 0usize;
    let mut byte_end = 0usize;
    for (i, c) in s.char_indices() {
        let w = if c as u32 > 0xFFFF { 2 } else { 1 };
        if units + w > n {
            return &s[..byte_end];
        }
        units += w;
        byte_end = i + c.len_utf8();
    }
    s
}

/// 等价于 Kotlin 的 `takeLastSafe(n)` / `takeLast(n)`：按 UTF-16 单元数取**后缀**。
///
/// 截点落进代理对中间时，Kotlin 的规则是"丢掉开头那半个低代理"（也就是从下一个字符开始），
/// 这里同一口径 —— 半截 emoji 发进 JSON 流前端解不出来，宁少勿残。
pub fn utf16_take_last(s: &str, n: usize) -> String {
    let total = utf16_len(s);
    if total <= n {
        return s.to_string();
    }
    let skip = total - n;
    let mut units = 0usize;
    for (i, c) in s.char_indices() {
        if units == skip {
            return s[i..].to_string();
        }
        let w = if c as u32 > 0xFFFF { 2 } else { 1 };
        if units + w > skip {
            // skip 落在这个宽字符内部 → 半个代理丢掉，从这个字符**之后**开始
            return s[i + c.len_utf8()..].to_string();
        }
        units += w;
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bmp_counts_one_unit_per_char() {
        // 汉字在 UTF-16 里每个 1 单元，所以 take(2) 取到的是两个汉字
        assert_eq!(utf16_take("中文abc", 2), "中文");
        assert_eq!(utf16_take("中文abc", 3), "中文a");
        assert_eq!(utf16_take("中文abc", 4), "中文ab");
        assert_eq!(utf16_take("中文abc", 5), "中文abc");
    }

    #[test]
    fn astral_chars_take_two_units() {
        // 😀 = U+1F600，UTF-16 里是代理对（2 单元），UTF-8 里 4 字节，Rust 里 1 个 char
        let s = "a😀b";
        assert_eq!(s.chars().count(), 3);
        assert_eq!(s.len(), 6);
        assert_eq!(utf16_take(s, 2), "a"); // 再放 😀 要 2 单元，超出
        assert_eq!(utf16_take(s, 3), "a😀");
        assert_eq!(utf16_take(s, 4), "a😀b");
    }

    #[test]
    fn take_beyond_length_returns_whole() {
        assert_eq!(utf16_take("短", 90), "短");
    }

    #[test]
    fn tail_counts_units_and_never_leaves_half_an_emoji() {
        assert_eq!(utf16_take_last("abc", 2), "bc");
        assert_eq!(utf16_take_last("中文abc", 3), "abc");
        assert_eq!(utf16_take_last("a😀b", 1), "b");
        assert_eq!(utf16_take_last("短", 90), "短");
        // Kotlin 的口径（逐单元推过一遍）：
        // "a😀b" = [a,D83D,DE00,b]，takeLast(2) 起点 2 是**低**代理 → 丢掉半个 → "b"
        assert_eq!(utf16_take_last("a😀b", 2), "b");
        // "ab😀c" = [a,b,D83D,DE00,c]，takeLast(3) 起点 2 是高代理 → 不是孤儿，整对留着 → "😀c"
        assert_eq!(utf16_take_last("ab😀c", 3), "😀c");
        // takeLast(2) 起点 3 是低代理 → 丢掉 → "c"
        assert_eq!(utf16_take_last("ab😀c", 2), "c");
    }
}
