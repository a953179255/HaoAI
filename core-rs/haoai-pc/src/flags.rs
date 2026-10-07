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

    /// 设置页上那一行的名字与说明。**逐字照抄 `HaoFlag` 的构造参数** ——
    /// 这两列是用户判断"要不要拨它"的唯一依据，改一个字都是在改产品文案。
    pub fn title(self) -> &'static str {
        match self {
            Flag::ToolResultSpill => "工具完整输出落文件",
            Flag::OutsideWrite => "允许写到工作区外",
            Flag::PlanMode => "计划模式",
            Flag::SnapshotBeforeWrite => "改文件前留快照",
            Flag::BrowserControl => "浏览器控制（CDP）",
            Flag::DesktopControl => "屏幕与点击控制",
            Flag::McpClient => "外部 MCP 工具",
        }
    }

    pub fn what(self) -> &'static str {
        match self {
            Flag::ToolResultSpill => {
                "命令/读取输出过长时，完整内容存进工作区 .haoai-output/，对话里只留头尾摘要和路径。"
            }
            Flag::OutsideWrite => {
                "关掉时，自动档碰到工作区之外的写路径会当场拒绝（问人那一档仍然弹卡）。要往仓库外落文件再打开它。"
            }
            Flag::PlanMode => "先只读调研并产出方案，经你确认后才允许改文件与执行命令。",
            Flag::SnapshotBeforeWrite => "覆盖/删除已有文件前先存一份到 .haoai-snap/，改坏了能回滚。",
            Flag::BrowserControl => {
                "让 agent 打开本机浏览器看网页、点按钮、截图。用独立的临时配置目录，不碰你自己的 Edge 登录态。默认关：关着时这个工具对模型不存在。"
            }
            Flag::DesktopControl => {
                "让 agent 截屏、列窗口、读控件树，并模拟点击与键盘输入。它能看见屏幕上所有内容，默认关；打开后每次点击/输入仍逐条问你。"
            }
            Flag::McpClient => {
                "连接你在 HAOAI_HOME/mcp.json 里配置的 MCP 服务器，把它们报的工具交给模型用。默认关：外部进程的行为不可预知，打开后每次调用仍逐条问你。"
            }
        }
    }

    /// 全量注册表：给"把 7 个开关列出来"的那一类消费方用（设置页 `GET /api/settings`、
    /// `haoai flags list`）。顺序就是 enum 的声明顺序 —— 设置页按这个顺序画行。
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
