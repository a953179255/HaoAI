//! 实验特性开关（`HaoFlag` 的 PC 侧等价物）：新能力先上再关，且能在 UI 里拨。
//!
//! 默认值就是安全边界的一部分，所以逐个抄死，不靠"读不到就当开"：
//! `Settings.flags` 里没写的那个键才回落到这里的 `default_on`。

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Flag {
    /// 工具输出超过落库上限时把全文写进工作区 `.haoai-output/`，会话里只留头尾摘要 + 路径。
    #[allow(dead_code)] // 溢出落文件那条路还没搬（`Engine.kt:999` 那侧）
    ToolResultSpill,
    /// 允许 agent 往**工作区之外**写文件。默认关。
    OutsideWrite,
    /// 计划模式。**Kotlin 侧也没有消费方**（只读是靠档位判的，不是靠这个开关），
    /// 列在这里是为了让两端的注册表是同一张表。
    #[allow(dead_code)]
    PlanMode,
    /// 破坏性操作前留快照（git 仓库里靠 git 自己，非 git 目录用影子副本）。
    SnapshotBeforeWrite,
    /// 浏览器控制（CDP）。
    BrowserControl,
    /// 屏幕理解 + 点击级自动化。
    DesktopControl,
    /// 外部 MCP 工具。
    #[allow(dead_code)] // MCP 客户端整个还没搬
    McpClient,
}

impl Flag {
    /// settings.json 里 `flags` 对象的键，与手机端同名同语义 —— 键名写错的表现是
    /// "用户拨了开关但没生效"，那种 bug 没有任何报错。
    pub fn key(self) -> &'static str {
        match self {
            Flag::ToolResultSpill => "tool_result_spill",
            Flag::OutsideWrite => "outside_write",
            Flag::PlanMode => "plan_mode",
            Flag::SnapshotBeforeWrite => "snapshot_before_write",
            Flag::BrowserControl => "browser_control",
            Flag::DesktopControl => "desktop_control",
            Flag::McpClient => "mcp_client",
        }
    }

    pub fn default_on(self) -> bool {
        match self {
            // 往外写、控浏览器、控屏幕、连外部进程：默认关，要显式打开
            Flag::OutsideWrite | Flag::BrowserControl | Flag::DesktopControl | Flag::McpClient => false,
            // 剩下三个是"默认就该有的保护/能力"
            Flag::ToolResultSpill | Flag::PlanMode | Flag::SnapshotBeforeWrite => true,
        }
    }

    /// 全量注册表：给"把 7 个开关列出来"的那一类消费方用（设置页、`haoai flags list`）。
    /// 那两条路由还在 Kotlin 那边，所以这个 const 暂时只有测试在读 —— **搬过来时删掉 allow**。
    #[allow(dead_code)]
    pub const ALL: [Flag; 7] = [
        Flag::ToolResultSpill,
        Flag::OutsideWrite,
        Flag::PlanMode,
        Flag::SnapshotBeforeWrite,
        Flag::BrowserControl,
        Flag::DesktopControl,
        Flag::McpClient,
    ];
}

/// `HaoFlag.enabled(flag, settings.flags)`。参数是覆盖表而不是整个 Settings：
/// 闸口那边只用到这一件事，传一大坨会把"哪些开关影响安全"这件事藏起来。
pub fn enabled(flag: Flag, overrides: &[(String, bool)]) -> bool {
    overrides
        .iter()
        .find(|(k, _)| k == flag.key())
        .map(|(_, v)| *v)
        .unwrap_or_else(|| flag.default_on())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn overrides_from(json: &str) -> Vec<(String, bool)> {
        let dir = std::env::temp_dir().join(format!("haoai-flags-{}-{}", json.len(), std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("settings.json"), json).unwrap();
        let s = crate::store::Store::new(PathBuf::from(&dir)).settings();
        let _ = fs::remove_dir_all(&dir);
        s.flags
    }

    #[test]
    fn defaults_come_from_the_registry_not_from_missing_keys() {
        let f = overrides_from(r#"{"baseUrl":"http://x/v1","model":"m"}"#);
        assert!(!enabled(Flag::OutsideWrite, &f), "往外写默认必须关着");
        assert!(enabled(Flag::SnapshotBeforeWrite, &f), "默认关着快照就等于改坏了没法回滚");
        assert!(enabled(Flag::ToolResultSpill, &f));
    }

    /// 用户那份 settings.json 里 flags 是真值布尔（`"browser_control":true`），
    /// Kotlin 却按 `jsonPrimitive.content == "true"` 比字符串 —— 两端都得读出同一个结果。
    #[test]
    fn a_real_settings_file_overrides_the_defaults() {
        let f = overrides_from(
            r#"{"baseUrl":"http://x/v1","model":"m","flags":{"outside_write":true,"plan_mode":false,"browser_control":"true","mcp_client":1}}"#,
        );
        assert!(enabled(Flag::OutsideWrite, &f));
        assert!(!enabled(Flag::PlanMode, &f));
        // 字符串 "true" 按 Kotlin 的比法算开；数字 1 不算
        assert!(enabled(Flag::BrowserControl, &f), "jsonPrimitive.content==\"true\" 的口径");
        assert!(!enabled(Flag::McpClient, &f), "1 不等于 true");
        // 没写的那一项照旧回落默认
        assert!(enabled(Flag::SnapshotBeforeWrite, &f));
    }

    #[test]
    fn flag_keys_are_the_same_strings_the_phone_and_cli_write() {
        for fl in Flag::ALL {
            assert!(fl.key().chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_'),
                "旗标键名必须是纯小写下划线：{}", fl.key());
        }
        assert_eq!(Flag::ALL.len(), 7, "HaoFlag 一共 7 条，少一条就是有两端对不上的开关");
    }
}
