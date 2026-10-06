// 从 pc/src/main/kotlin/com/haoai/pc/Tools.kt 抽出工具注册表，生成 core-rs/haoai-pc/src/tools.rs
// 用法：node pc/tools/gen-rust-tools.mjs
// 为什么生成而不是手抄：32 条 name/kind/desc 与 9 组 category 全是 Kotlin 侧的字面量，
// 手抄必然漂；生成物配一条对金标准的差分测试，漂了立刻红。
import fs from "node:fs";

const KT = "pc/src/main/kotlin/com/haoai/pc/Tools.kt";
const AC = "pc/src/main/kotlin/com/haoai/pc/AgentConfig.kt";
// Tool 子类散在多个文件里（Media/Browser/Pty/CronTools/…），必须全目录扫
const DIR = "pc/src/main/kotlin/com/haoai/pc";
const src = fs.readFileSync(KT, "utf8");
const allKt = fs.readdirSync(DIR).filter(f => f.endsWith(".kt"))
  .map(f => fs.readFileSync(`${DIR}/${f}`, "utf8")).join("\n");

/** 从 open 位置开始找匹配的右括号，返回 [内容, 结束下标] */
function balanced(s, open) {
  let d = 0, i = open, q = false;
  for (; i < s.length; i++) {
    const c = s[i];
    if (c === "\\") { i++; continue; }
    if (c === '"') q = !q;
    if (q) continue;
    if (c === "(") d++;
    else if (c === ")") { d--; if (d === 0) return [s.slice(open + 1, i), i]; }
  }
  throw new Error("括号不配对");
}

/** 按顶层逗号切参数（忽略括号内与字符串内的逗号） */
function splitArgs(t) {
  const out = []; let d = 0, q = false, cur = "";
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (c === "\\") { cur += c + (t[i + 1] ?? ""); i++; continue; }
    if (c === '"') { q = !q; cur += c; continue; }
    if (q) { cur += c; continue; }
    if ("([{".includes(c)) d++;
    if (")]}".includes(c)) d--;
    if (c === "," && d === 0) { out.push(cur); cur = ""; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur);
  return out;
}

/** Kotlin 反转义：`\$` `\"` `\\` `\'` 都是"下一个字符本身"，\n \t \r 是控制符。
 *  漏掉 `\$` 会让 desc 多出一个 UTF-16 单元，把 take(90) 的截断点顶偏一位——
 *  表现为"既多了个反斜杠、又少了末尾一个字"，一个 bug 两个症状。 */
function unescapeKotlin(t) {
  let out = "";
  for (let i = 0; i < t.length; i++) {
    if (t[i] !== "\\") { out += t[i]; continue; }
    const n = t[++i];
    out += n === "n" ? "\n" : n === "t" ? "\t" : n === "r" ? "\r" : n;
  }
  return out;
}

/** 把 Kotlin 的字符串拼接表达式求成值；遇到模板就报错，不猜 */
function evalStr(expr, who) {
  if (/\$\{|\$[A-Za-z_]/.test(expr.replace(/\\\$/g, ""))) {
    throw new Error(`${who}: desc 里有 Kotlin 字符串模板，无法静态求值 -> ${expr.slice(0, 80)}`);
  }
  const parts = expr.match(/"(?:[^"\\]|\\.)*"/g);
  if (!parts) throw new Error(`${who}: 找不到字符串字面量`);
  return parts.map(p => unescapeKotlin(p.slice(1, -1))).join("");
}

// ask_user / ask_user_batch 的 params 不走 schema(...) 的极简形状，而是手写 buildJsonObject
// 的嵌套结构（options 是 {label,description} 对象数组、与手机端逐字节同款）。
// 静态解析那种嵌套字面量不划算，这里直接转写 kotlinx JsonObject.toString() 的结果，
// 键序 = put 的声明序。正确性由 golden 的「工具说明」字数兜底：转写错一个字总数就对不上。
const PARAMS_OVERRIDE = {
  ask_user: '{"type":"object","properties":{"question":{"type":"string","description":"完整提问：一句话说清背景与要决定的事，以问号结尾"},"options":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string","description":"选项短标签（≤12 字），卡片上直接显示"},"description":{"type":"string","description":"该选项的含义/代价/后果，一行话；可省略"}},"required":["label"]},"description":"2~4 个互斥选项；推荐项放第一个"},"allow_free_text":{"type":"boolean","description":"是否允许用户自由输入其他回答，默认 true"},"confirm":{"type":"boolean","description":"是否需要用户点选后再按确认按钮（防误触）。默认 true。低风险、选错也无代价的事实/偏好选择（如早上还是晚上）设 false：用户点选项即回答、任务立刻继续；删除/覆盖/花钱等不可逆或高代价的分叉必须保持 true"},"recommend":{"type":"boolean","description":"是否给第一个选项标「推荐」徽标，默认 true。仅当你确实倾向该选项时才标；各选项无优劣之分的问题（测试问卷、量表打分、抽签类）设 false，不要标推荐"}},"required":["question","options"]}',
  ask_user_batch: '{"type":"object","properties":{"title":{"type":"string","description":"题组标题，显示在卡片头部（如「MBTI 性格测试」）"},"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string","description":"题干，一句话，以问号或句号结尾"},"options":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string","description":"选项短标签（≤12 字）"},"description":{"type":"string","description":"该选项含义的一行补充，可省略"}},"required":["label"]},"description":"2~6 个互斥选项，各题可不等长"}},"required":["question","options"]},"description":"1~100 道题，按出题顺序排列"},"allow_free_text":{"type":"boolean","description":"每题是否提供「其他…（自由输入）」出口，默认 false"}},"required":["title","questions"]}',
};

/** 解析 schema("a" to "string", ..., required = arrayOf("a")) 成 kotlinx 的紧凑 JSON */
function schemaJson(expr) {
  const [inner] = balanced(expr, expr.indexOf("("));
  const props = [];
  let required = [];
  for (const raw of splitArgs(inner)) {
    const a = raw.trim();
    if (!a) continue;
    const rm = a.match(/^required\s*=\s*arrayOf\(([\s\S]*)\)$/);
    if (rm) {
      required = [...rm[1].matchAll(/"([^"]*)"/g)].map(x => x[1]);
      continue;
    }
    const pm = a.match(/^\s*"([^"]+)"\s+to\s+"([^"]+)"\s*$/);
    if (!pm) throw new Error(`schema() 里认不出的参数形态：${a.slice(0, 60)}`);
    props.push([pm[1], pm[2]]);
  }
  let j = '{"type":"object","properties":{' +
    props.map(([k, t]) => `${JSON.stringify(k)}:{"type":${JSON.stringify(t)}}`).join(",") + "}}";
  if (required.length) {
    j = j.slice(0, -1) + ',"required":[' + required.map(r => JSON.stringify(r)).join(",") + "]}";
  }
  return j;
}

// 1) 每个 Tool 子类：class X : Tool( "name", "desc", schema(...), kind = "..." )
const found = new Map();
for (const m of allKt.matchAll(/class\s+(\w+)\s*:\s*Tool\s*\(/g)) {
  const cls = m[1];
  const [body] = balanced(allKt, allKt.indexOf("(", m.index + cls.length + 7));
  const args = splitArgs(body);
  const name = evalStr(args[0] ?? "", cls);
  const desc = args.length > 1 ? evalStr(args[1], cls) : "";
  const km = body.match(/\bkind\s*=\s*"([^"]+)"/);
  let params;
  const third = (args[2] ?? "").trim();
  if (PARAMS_OVERRIDE[name]) params = PARAMS_OVERRIDE[name];
  else if (third.startsWith("schema(")) params = schemaJson(third);
  else throw new Error(`${cls}: params 既不是 schema() 也没有转写表项（${third.slice(0, 40)}）`);
  found.set(cls, { name, desc, kind: km ? km[1] : "read", params });
}

// 2) 注册顺序 = builtinTools() 里的类名顺序
const reg = src.slice(src.indexOf("fun builtinTools()"));
const order = [...reg.matchAll(/(\w+)\(\)/g)].map(m => m[1]).filter(c => found.has(c));
if (order.length !== 32) throw new Error(`builtinTools 解析到 ${order.length} 个，应为 32`);

// 3) categoryOf 的表
const cats = new Map();
const cb = src.slice(src.indexOf("val TOOL_CATEGORIES"), src.indexOf("fun categoryOf"));
for (const m of cb.matchAll(/listOf\(([^)]*)\)\.forEach\s*\{\s*put\(it,\s*"([^"]+)"\)/g)) {
  for (const n of m[1].matchAll(/"([^"]+)"/g)) cats.set(n[1], m[2]);
}
for (const m of cb.matchAll(/put\("([^"]+)",\s*"([^"]+)"\)/g)) cats.set(m[1], m[2]);

// 4) CRITICAL
const ac = fs.readFileSync(AC, "utf8");
const crit = new Set([...(ac.match(/val CRITICAL = setOf\(([^)]*)\)/)?.[1] ?? "").matchAll(/"([^"]+)"/g)].map(m => m[1]));

const rustStr = s => JSON.stringify(s).replace(/\n/g, "\\n").replace(/\t/g, "\\t");
const rows = order.map(c => found.get(c));
const out = `// 由 pc/tools/gen-rust-tools.mjs 从 Tools.kt / AgentConfig.kt 生成，勿手改。
// 重新生成：node pc/tools/gen-rust-tools.mjs
// 校验：cargo test --release（golden 的「工具说明」字数 + /api/state 逐字节门禁）

use crate::utf16::{utf16_len, utf16_take};

pub struct ToolMeta {
    pub name: &'static str,
    pub kind: &'static str,
    pub desc: &'static str,
    pub params: &'static str,
    pub category: &'static str,
    pub critical: bool,
}

pub const TOOLS: [ToolMeta; ${rows.length}] = [
${rows.map(r => `    ToolMeta { name: ${rustStr(r.name)}, kind: ${rustStr(r.kind)}, desc: ${rustStr(r.desc)}, params: ${rustStr(r.params)}, category: ${rustStr(cats.get(r.name) ?? "外部（MCP）")}, critical: ${crit.has(r.name)} },`).join("\n")}
];

/// \`Engine.readOnlyTool\`：计划模式允许哪几把 —— 只读类，加计划本身要用的两件。
/// 生成的表里 todo / ask_user / ask_user_batch 的 kind 本来就是 read，后两个条件看着多余；
/// 留着是照抄 Kotlin：**判据只写一遍**，可见性过滤（\`schemas()\`）与执行侧闸口（\`exec\`）
/// 共用它，否则会出现"模型看得见却调不动"或"看不见却调得动"两种裂缝。
pub fn read_only(t: &ToolMeta) -> bool {
    t.kind == "read" || t.name == "todo" || t.name == "ask_user"
}

/// \`Server.stateJson\` 的 tools 段：\`desc.take(90)\` 是 **UTF-16 单元**意义上的截断，
/// 32 条里有 17 条会被截，所以必须走 utf16_take 而不是 chars().take()。
/// 注意与 \`schemas_string\` 的分工：\`toolInfos()\` 是 **map 全部工具并标 off**（界面要能
/// 看见被关掉的那几把好再打开），只有喂给模型的 \`schemas()\` 才把 off 过滤掉。
pub fn tools_json(off: &[&str]) -> String {
    TOOLS.iter().map(|t| {
        format!(
            "{{\\"name\\":{},\\"kind\\":{},\\"desc\\":{},\\"off\\":{},\\"gated\\":{},\\"category\\":{},\\"critical\\":{}}}",
            crate::state::quote(t.name),
            crate::state::quote(t.kind),
            crate::state::quote(utf16_take(t.desc, 90)),
            off.contains(&t.name),
            false,
            crate::state::quote(t.category),
            t.critical
        )
    }).collect::<Vec<_>>().join(",")
}

/// 复刻 \`Engine.schemas().toString()\`：\`ToolSchema\` 是 data class，Kotlin 生成的
/// \`toString()\` 形如 \`[ToolSchema(name=read, desc=…, params={…}), …]\`，
/// **desc 原样拼进去、不做任何转义**（含换行与逗号）。
/// \`contextBreakdown\` 里的「工具说明」字数量的就是这个串的 UTF-16 长度，
/// 所以要连这个非 JSON 的格式一起复刻，不能图省事序列化成 JSON。
pub fn schemas_string(off: &[&str]) -> String {
    let items: Vec<String> = TOOLS.iter().filter(|t| !off.contains(&t.name))
        .map(|t| format!("ToolSchema(name={}, desc={}, params={})", t.name, t.desc, t.params))
        .collect();
    format!("[{}]", items.join(", "))
}

/// 「工具说明」的字数（UTF-16 单元，与 Kotlin \`String.length\` 同口径）。
pub fn schemas_chars(off: &[&str]) -> usize {
    utf16_len(&schemas_string(off))
}
`;
fs.writeFileSync("core-rs/haoai-pc/src/tools.rs", out, "utf8");
console.log(`生成 ${rows.length} 条；category 覆盖 ${cats.size} 条；CRITICAL ${crit.size} 条`);
console.log(`desc 会被截断的条数：${rows.filter(r => Array.from(r.desc).reduce((a, c) => a + (c.codePointAt(0) > 0xFFFF ? 2 : 1), 0) > 90).length}`);
const jLen = rows.map(r => `ToolSchema(name=${r.name}, desc=${r.desc}, params=${r.params})`);
const total = [...("[" + jLen.join(", ") + "]")].reduce((a, c) => a + (c.codePointAt(0) > 0xFFFF ? 2 : 1), 0);
console.log(`schemas().toString() 的 UTF-16 长度 = ${total}  （JVM 金标准应为 12073）`);
