package com.haoai.agent.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 自研代码高亮（v3：语言档案化）。
 *
 * 线性正则分词 + 按语言档案（Spec）判定，零依赖、单次扫描（流式逐帧调用，闭合块走 LRU 缓存）。
 * v3 核心修正（旧版探针实测缺陷，判定口径由 CodeHighlightTest 钉死）：
 * - 注释/引号/特殊 token **全部按语言开关**；未收录语言 = 100% 纯文本（旧版 #、//、' 规则
 *   不分语言全局生效，说明文字块、CSS 色值、Rust 属性宏大面积被误染——真降级承诺 v3 兑现）。
 * - 补 /* */ 块注释；js 模板串允许跨行；`https://` 的 `//` 不再被当行注释（冒号守卫）。
 * - 引号串要求"字符串起始位"：前一非空白字符是字母数字/下划线则拒绝（It's / Bob's 不劈成串）；
 *   单引号内容排除 <>()&——Rust 生命周期 `&'a mut` 不再被配成跨词假字符串。
 * - JSON/YAML/CSS 的键（"k": 与裸 k: 两形态）染函数蓝；关键字/literal 优先于键位。
 * - CSS #rrggbb 染数字橙、@rule 蓝；rust #[attr] 注解蓝；c/cpp #include 预处理蓝——均非注释。
 * - sql/dockerfile 关键字大小写不敏感；数字带单位整体染橙（42px / 18.5dp / 135deg）。
 * 换词表请同步补测试；本 object 无 Android 设备依赖，可纯 JVM 断言。
 */
object CodeHighlight {

    /** 语言档案：一个语言"允许哪些 token 形状、词表是什么"的完整声明。 */
    private class Spec(
        val keywords: Set<String> = emptySet(),
        val builtins: Set<String> = emptySet(),
        val literals: Set<String> = emptySet(),
        /** 定义处染蓝的前词（fun/def/func/class…） */
        val defPrev: Set<String> = emptySet(),
        /** 调用处 `name(` 染蓝（hljs 仅 js/ts 语法带此规则；css 也用作函数蓝） */
        val callSiteBlue: Boolean = false,
        /** 行注释前缀（"//"、"#"、"--"）；无 = 该语言不认注释 */
        val lineComments: Set<String> = emptySet(),
        /** 有 /* */ 块注释 */
        val blockComment: Boolean = false,
        /** 合法字符串引号字符 */
        val quotes: Set<Char> = emptySet(),
        /** @注解/装饰器/at-rule */
        val annotationAt: Boolean = false,
        /** #[attr]（rust）= 注解 */
        val hashAttr: Boolean = false,
        /** #include 类预处理指令（c/cpp）= 注解蓝 */
        val preproc: Boolean = false,
        /** #rrggbb 十六进制颜色（css）= 数字橙 */
        val hashColor: Boolean = false,
        /** 裸标识符后跟 `:` 视为属性名/键 = 函数蓝（css/js/ts/yaml） */
        val propKeys: Boolean = false,
        /** 关键字大小写不敏感（sql/dockerfile） */
        val caseInsensitive: Boolean = false,
    )

    /** 围栏语言标签 → 档案（别名直接并入表，替代旧 langAlias）。 */
    private val langs: Map<String, Spec> = run {
        val core = mapOf(
            "kotlin" to Spec(
                keywords = setOf(
                    "package", "import", "class", "object", "interface", "fun", "val", "var",
                    "if", "else", "when", "for", "while", "do", "return", "break", "continue",
                    "is", "in", "as", "this", "super", "private", "public", "internal",
                    "protected", "override", "open", "abstract", "sealed", "data", "companion",
                    "init", "suspend", "lateinit", "by", "try", "catch", "finally", "throw",
                    "typealias", "operator", "inline", "const"
                ),
                builtins = setOf(
                    "println", "print", "listOf", "mapOf", "setOf", "mutableListOf",
                    "mutableMapOf", "mutableSetOf", "arrayOf", "intArrayOf", "emptyList",
                    "emptyMap", "emptySet", "requireNotNull", "checkNotNull", "lazy", "run",
                    "let", "also", "apply", "with", "takeIf", "takeUnless", "repeat",
                    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte",
                    "Short", "Any", "Unit", "Pair", "Triple"
                ),
                literals = setOf("true", "false", "null"),
                defPrev = setOf("fun", "class", "interface", "object"),
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\''), annotationAt = true,
            ),
            "java" to Spec(
                keywords = setOf(
                    "package", "import", "class", "interface", "enum", "record", "public",
                    "private", "protected", "static", "final", "void", "int", "long", "double",
                    "float", "boolean", "char", "byte", "short", "new", "return", "if", "else",
                    "for", "while", "do", "switch", "case", "break", "continue", "try", "catch",
                    "finally", "throw", "throws", "extends", "implements", "this", "super",
                    "abstract", "synchronized", "volatile", "instanceof"
                ),
                builtins = setOf(
                    "System", "String", "Math", "Integer", "Long", "Double", "Float",
                    "Boolean", "Character", "Byte", "Short", "StringBuilder", "StringBuffer",
                    "List", "Map", "Set", "ArrayList", "HashMap", "HashSet", "Optional", "Objects"
                ),
                literals = setOf("true", "false", "null"),
                defPrev = setOf("class", "interface", "enum", "record", "new", "void"),
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"'), annotationAt = true,
            ),
            "python" to Spec(
                keywords = setOf(
                    "def", "class", "import", "from", "as", "return", "if", "elif", "else",
                    "for", "while", "break", "continue", "pass", "in", "is", "not", "and",
                    "or", "try", "except", "finally", "raise", "with", "lambda", "yield",
                    "global", "nonlocal", "async", "await", "del", "assert", "match", "case"
                ),
                builtins = setOf(
                    "print", "len", "range", "int", "float", "str", "bool", "list", "dict",
                    "set", "tuple", "sum", "min", "max", "sorted", "reversed", "enumerate",
                    "zip", "map", "filter", "abs", "round", "open", "type", "isinstance",
                    "hasattr", "getattr", "setattr", "input", "any", "all", "ord", "chr",
                    "bytes", "bytearray", "staticmethod", "classmethod", "super"
                ),
                literals = setOf("True", "False", "None"),
                defPrev = setOf("def", "class"),
                lineComments = setOf("#"),
                quotes = setOf('"', '\''), annotationAt = true,
            ),
            "js" to Spec(
                keywords = setOf(
                    "const", "let", "var", "function", "class", "return", "if", "else", "for",
                    "while", "do", "break", "continue", "new", "this", "try", "catch", "finally",
                    "throw", "typeof", "instanceof", "import", "export", "from", "default",
                    "async", "await", "yield", "extends", "static", "get", "set", "of", "in",
                    "delete", "void"
                ),
                builtins = setOf(
                    "console", "JSON", "Math", "Date", "Promise", "Array", "Object", "String",
                    "Number", "Boolean", "Set", "Map", "parseInt", "parseFloat", "setTimeout",
                    "setInterval", "clearTimeout", "clearInterval", "require", "alert", "Symbol",
                    "Reflect", "Proxy"
                ),
                literals = setOf("true", "false", "null", "undefined", "NaN", "Infinity"),
                defPrev = setOf("function", "class"),
                callSiteBlue = true, propKeys = true,
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\'', '`'),
            ),
            "ts" to Spec(
                keywords = setOf(
                    "const", "let", "var", "function", "class", "interface", "type", "enum",
                    "return", "if", "else", "for", "while", "do", "break", "continue", "new",
                    "this", "try", "catch", "finally", "throw", "typeof", "instanceof",
                    "import", "export", "from", "default", "async", "await", "extends",
                    "implements", "public", "private", "protected", "readonly", "static",
                    "as", "keyof", "namespace", "declare", "abstract"
                ),
                builtins = setOf(
                    "console", "JSON", "Math", "Date", "Promise", "Array", "Object", "String",
                    "Number", "Boolean", "Set", "Map", "fetch", "parseInt", "parseFloat",
                    "setTimeout", "setInterval", "Partial", "Readonly", "Record", "Pick", "Omit"
                ),
                literals = setOf("true", "false", "null", "undefined", "NaN", "Infinity"),
                defPrev = setOf("function", "class"),
                callSiteBlue = true, propKeys = true,
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\'', '`'),
            ),
            "json" to Spec(
                literals = setOf("true", "false", "null"),
                quotes = setOf('"'),
            ),
            "yaml" to Spec(
                literals = setOf("true", "false", "null", "yes", "no"),
                lineComments = setOf("#"),
                quotes = setOf('"', '\''), propKeys = true,
            ),
            "bash" to Spec(
                keywords = setOf(
                    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
                    "esac", "function", "return", "export", "local", "echo", "cd", "sudo",
                    "apt", "apt-get", "git", "npm", "pip", "python", "curl", "wget"
                ),
                builtins = setOf(
                    "cat", "ls", "grep", "sed", "awk", "find", "sort", "uniq", "head", "tail",
                    "wc", "chmod", "chown", "mkdir", "rmdir", "rm", "cp", "mv", "touch",
                    "systemctl", "journalctl", "docker", "kubectl", "ssh", "scp", "tar",
                    "zip", "unzip"
                ),
                lineComments = setOf("#"),
                quotes = setOf('"', '\''),
            ),
            "sql" to Spec(
                keywords = setOf(
                    "select", "from", "where", "insert", "into", "values", "update", "set",
                    "delete", "create", "table", "drop", "alter", "index", "view", "join",
                    "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
                    "limit", "offset", "and", "or", "not", "primary", "key", "foreign",
                    "references", "as", "distinct", "count", "sum", "avg", "min", "max"
                ),
                literals = setOf("null"),
                lineComments = setOf("--"), caseInsensitive = true,
                quotes = setOf('\'', '"'),
            ),
            "xml" to Spec(quotes = setOf('"', '\'')),
            "go" to Spec(
                keywords = setOf(
                    "package", "import", "func", "type", "struct", "interface", "map", "chan",
                    "go", "defer", "return", "if", "else", "for", "range", "switch", "case",
                    "default", "break", "continue", "var", "const"
                ),
                builtins = setOf(
                    "len", "cap", "make", "new", "append", "copy", "delete", "panic",
                    "recover", "print", "println", "close", "complex", "real", "imag",
                    "min", "max", "string", "int", "int8", "int16", "int32", "int64", "uint",
                    "float32", "float64", "bool", "byte", "rune", "error"
                ),
                literals = setOf("true", "false", "nil", "iota"),
                defPrev = setOf("func", "type"),
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\'', '`'),
            ),
            "cpp" to Spec(
                keywords = setOf(
                    "include", "using", "namespace", "class", "struct", "public", "private",
                    "protected", "virtual", "override", "template", "typename", "const",
                    "static", "void", "int", "long", "double", "float", "bool", "char", "auto",
                    "return", "if", "else", "for", "while", "do", "switch", "case", "break",
                    "continue", "try", "catch", "throw", "new", "delete", "this"
                ),
                builtins = setOf(
                    "std", "cout", "cin", "endl", "vector", "string", "map",
                    "set", "pair", "make_pair", "make_shared", "make_unique", "sort", "find",
                    "size_t", "uint32_t", "uint64_t"
                ),
                literals = setOf("true", "false", "nullptr"),
                defPrev = setOf("class", "struct", "new"),
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\''), preproc = true,
            ),
            "rust" to Spec(
                keywords = setOf(
                    "fn", "let", "mut", "struct", "enum", "impl", "trait", "pub", "use", "mod",
                    "match", "if", "else", "loop", "while", "for", "in", "ref", "move", "self",
                    "super", "crate", "async", "await", "unsafe", "dyn", "const", "static",
                    "type", "where", "as", "break", "continue", "return"
                ),
                builtins = setOf(
                    "Some", "None", "Ok", "Err", "String", "Vec", "HashMap", "HashSet",
                    "Option", "Result", "usize", "isize", "i8", "i16", "i32", "i64", "u8",
                    "u16", "u32", "u64", "f32", "f64", "bool", "char", "str"
                ),
                literals = setOf("true", "false"),
                defPrev = setOf("fn", "struct", "enum", "trait", "impl", "type"),
                lineComments = setOf("//"), blockComment = true,
                quotes = setOf('"', '\''), hashAttr = true,
            ),
            "css" to Spec(
                blockComment = true, quotes = setOf('"', '\''),
                annotationAt = true, hashColor = true, propKeys = true, callSiteBlue = true,
            ),
            "dockerfile" to Spec(
                keywords = setOf(
                    "from", "run", "cmd", "label", "maintainer", "expose", "env", "add",
                    "copy", "entrypoint", "volume", "user", "workdir", "onbuild", "shell",
                    "arg", "stopsignal", "healthcheck"
                ),
                lineComments = setOf("#"), quotes = setOf('"', '\''), caseInsensitive = true,
            ),
            "toml" to Spec(
                literals = setOf("true", "false"),
                lineComments = setOf("#"), quotes = setOf('"', '\''),
            ),
        )
        // 围栏标签写法繁多：别名并入同一档案（旧 langAlias 的替代）
        core + mapOf(
            "kt" to core.getValue("kotlin"), "kts" to core.getValue("kotlin"),
            "py" to core.getValue("python"), "python3" to core.getValue("python"),
            "javascript" to core.getValue("js"), "jsx" to core.getValue("js"),
            "node" to core.getValue("js"),
            "typescript" to core.getValue("ts"), "tsx" to core.getValue("ts"),
            "shell" to core.getValue("bash"), "sh" to core.getValue("bash"),
            "zsh" to core.getValue("bash"), "console" to core.getValue("bash"),
            "yml" to core.getValue("yaml"),
            "html" to core.getValue("xml"), "svg" to core.getValue("xml"),
            "golang" to core.getValue("go"), "c++" to core.getValue("cpp"),
            "c" to core.getValue("cpp"),
            "docker" to core.getValue("dockerfile"),
            "ini" to core.getValue("toml"),
        )
    }

    /**
     * 分词正则（Java 方言，alternation 左起优先）。**形状在此全枚举，是否生效按语言档案判定**——
     * classify 不认的形状保持 plain（代价是被消费的子串不再细分，注释行内词除外）。
     * - `(?<!:)//` 冒号守卫：`https://x` 的 `//` 不再吞成注释。
     * - 单引号内容排除 `<>()&`：Rust 生命周期 `&'a` 不再配成跨词假字符串（代价：`'<'` 类字符字面量落 plain）。
     * - 十六进制色值 `#abc..#rrggbbaa` 先于 `#[A-Za-z]…` 词形，预处理指令（#define）走词形不被色值抢。
     * - 数字带单位整体成 token（42px / 18.5dp / 135deg / 0.7f）。
     */
    private val tokenRegex = Regex(
        "/\\*[\\s\\S]*?\\*/" +                                  // 块注释（跨行）
            "|\"\"\"[\\s\\S]*?\"\"\"" +                        // python 三引号 docstring
            "|\"(?:\\\\.|[^\"\\\\\n])*\"" +                    // 双引号串
            "|'(?:\\\\.|[^'\\\\\n<>()&])*'" +                  // 单引号串（排除式内容防生命周期假配）
            "|`(?:\\\\.|[^`\\\\])*`" +                         // js/go 反引号串（允许跨行）
            "|#\\[[^\\]]*\\]" +                                 // rust #[attr]
            "|#[0-9a-fA-F]{3,8}\\b" +                           // 十六进制色值形
            "|#[A-Za-z][^\\n]*" +                               // 指令形（c/cpp #include；bash 里是词形注释的一部分）
            "|#[^\\n]*" +                                       // 通用 # 注释（含 bash "# word…" 整段）
            "|-- [^\\n]*" +                                     // sql 行注释（"-- "带空格，防 i-- 误吞）
            "|(?<!:)//[^\\n]*" +                                // // 行注释（冒号守卫）
            "|@[A-Za-z][A-Za-z0-9_]*" +                         // 注解/装饰器/at-rule
            "|\\b0x[0-9a-fA-F]+\\b" +                           // 十六进制字面量
            "|\\b\\d+(?:\\.\\d+)?(?:[a-zA-Z%]{1,3}|\\.\\.\\.)?\\b" + // 数字（含单位：42px/18.5dp/135deg/0.7f）
            "|[A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*" +     // 标识符（仅合连字符 apt-get；点号分开保 Math.PI）
            "|\\b\\d+\\b"                                       // 兜底数字
    )

    /** 高亮配色：跟随深浅色主题由调用方传入（MarkdownText 按当前主题取色）。 */
    data class Colors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color,
        val plain: Color,
        /** 内置函数/类型（print/len/int/console，对齐 highlight.js built_in #C18401）。 */
        val builtin: Color,
        /** 函数名/属性键（蓝）。 */
        val function: Color,
        /** 布尔/null 字面量。 */
        val literal: Color
    )

    /** 深色主题配色（Atom One Dark 官方值）。 */
    fun darkColors() = Colors(
        keyword = Color(0xFFC678DD),
        string = Color(0xFF98C379),
        comment = Color(0xFF5C6370),
        number = Color(0xFFD19A66),
        annotation = Color(0xFF61AFEF),
        plain = Color(0xFFABB2BF),
        builtin = Color(0xFFE6C07B),
        function = Color(0xFF61AFEF),
        literal = Color(0xFFC678DD)
    )

    /** 浅色主题配色（AtomOne Light 官方值：紫关键字/绿串/赭黄内置/蓝函数名）。 */
    fun lightColors() = Colors(
        keyword = Color(0xFFA626A4),
        string = Color(0xFF50A14F),
        comment = Color(0xFFA0A1A7),
        number = Color(0xFF986801),
        annotation = Color(0xFF4078F2),
        plain = Color(0xFF383A42),
        builtin = Color(0xFFC18401),
        function = Color(0xFF4078F2),
        literal = Color(0xFF0184BB)
    )

    // 高亮结果 LRU：Colors 为 data class 可作 key；仅闭合代码块走缓存（调用方控制），
    // 流式分片不进来防污染。pop 重建聊天页时历史代码块直接命中。
    private val highlightCache: MutableMap<Triple<String, String, Colors>, AnnotatedString> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Triple<String, String, Colors>, AnnotatedString>(48, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, Colors>, AnnotatedString>) =
                    size > 48
            }
        )

    /** 带缓存的版本：仅用于已闭合（closed=true）的代码块。 */
    fun highlightCached(code: String, langRaw: String, colors: Colors): AnnotatedString {
        val key = Triple(code, langRaw, colors)
        highlightCache[key]?.let { return it }
        val result = highlight(code, langRaw, colors)
        highlightCache[key] = result
        return result
    }

    /** 生成高亮 AnnotatedString；语言档案未收录时返回纯文本色（真降级，旧全局规则不残留）。 */
    fun highlight(code: String, langRaw: String, colors: Colors): AnnotatedString {
        val spec = langs[langRaw.lowercase()]
        if (spec == null) return buildAnnotatedString {
            withStyle(SpanStyle(color = colors.plain)) { append(code) }
        }
        return buildAnnotatedString {
            var index = 0
            var prevWord = ""
            for (m in tokenRegex.findAll(code)) {
                if (m.range.first > index) {
                    appendStyle(code.substring(index, m.range.first), colors.plain)
                }
                val token = m.value
                val color = classify(token, m.range.first, code, spec, prevWord, colors)
                appendStyle(token, color ?: colors.plain)
                if (token.first().isLetterOrDigit() || token.first() == '_') prevWord = token
                index = m.range.last + 1
            }
            if (index < code.length) appendStyle(code.substring(index), colors.plain)
        }
    }

    /** token → 颜色；null = plain。形状命中但当前语言不允许 → plain（规则语言开关的核心）。 */
    private fun classify(
        token: String,
        start: Int,
        code: String,
        spec: Spec,
        prevWord: String,
        colors: Colors,
    ): Color? {
        val nextCh = code.getOrNull(start + token.length)
        // # 系：按形状细分（#[attr] / 色值形 / 词形指令或注释 / 通用注释），档案决定颜色
        if (token.startsWith("#[")) {
            return if (spec.hashAttr) colors.annotation
            else if ("#" in spec.lineComments) colors.comment else null
        }
        if (token.startsWith("#") && isHexColorShape(token)) {
            return if (spec.hashColor) colors.number
            else if ("#" in spec.lineComments) colors.comment else null
        }
        if (token.startsWith("#")) {
            return if (spec.preproc) colors.annotation
            else if ("#" in spec.lineComments) colors.comment else null
        }
        return when {
            token.startsWith("/*") ->
                if (spec.blockComment) colors.comment else null

            token.startsWith("//") ->
                if ("//" in spec.lineComments) colors.comment else null

            token.startsWith("-- ") ->
                if ("--" in spec.lineComments) colors.comment else null

            token.startsWith("@") ->
                if (spec.annotationAt) colors.annotation else null

            token.startsWith("\"\"\"") ->
                if ('"' in spec.quotes) colors.string else null

            token[0] == '"' || token[0] == '\'' || token[0] == '`' -> {
                if (token[0] in spec.quotes && stringStartOk(code, start)) {
                    // 键形态 "k": → attr 蓝；值形态保持绿
                    if (nextNonWs(code, start + token.length) == ':') colors.function else colors.string
                } else null
            }

            token.first().isDigit() -> colors.number

            else -> {
                val t = if (spec.caseInsensitive) token.lowercase() else token
                when {
                    // 关键字/literal 优先于键位：js 的 `default:`、yaml 的 `true:` 保持词表色
                    t in spec.keywords -> colors.keyword
                    t in spec.literals -> colors.literal
                    spec.propKeys && nextNonWs(code, start + token.length) == ':' -> colors.function
                    t in spec.builtins -> colors.builtin
                    spec.defPrev.isNotEmpty() && prevWord in spec.defPrev -> colors.function
                    spec.callSiteBlue && (nextCh == '(' ||
                        isArrowFnDef(code, start + token.length)) -> colors.function
                    else -> null
                }
            }
        }
    }

    /** `#abc`…`#rrggbbaa`：全 hex 且长度 4..9。#define 等词形不会被误判（含非 hex 字母）。 */
    private fun isHexColorShape(token: String): Boolean =
        token.length in 4..9 && token.drop(1).all { it in "0123456789abcdefABCDEF" }

    /**
     * 字符串起始位判定：引号的**紧邻前字符**若是 ASCII 字母/数字/下划线/反斜杠，
     * 那不是字符串开头而是撇号（It's / Bob's）。py/js 前缀串（f' r" rb'' 等）先跳过前缀字母再判。
     * 不跨空白回看——`echo 'hi'` 引号前是空格，合法字符串照常识别。
     */
    private fun stringStartOk(code: String, from: Int): Boolean {
        var p = from - 1
        var skipped = 0
        while (p >= 0 && skipped < 2 && code[p] in "fFbBrRuU") { p--; skipped++ }
        if (p < 0) return true
        val prev = code[p]
        return !isAsciiWord(prev) && prev != '\\'
    }

    private fun isAsciiWord(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun nextNonWs(code: String, from: Int): Char? {
        var i = from
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        return code.getOrNull(i)
    }

    /** js/ts/css：token 之后是否为 `= [async] (…) =>`（const fetchJson = async (url) =>… 定义名蓝）。 */
    private fun isArrowFnDef(code: String, from: Int): Boolean {
        var i = from
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        if (code.getOrNull(i) != '=' || code.getOrNull(i + 1) == '=') return false
        i++
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        if (code.startsWith("async", i)) {
            i += 5
            while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        }
        return code.getOrNull(i) == '(' && code.indexOf("=>", i) >= 0
    }

    private fun AnnotatedString.Builder.appendStyle(text: String, color: Color) {
        pushStyle(SpanStyle(color = color))
        append(text)
        pop()
    }
}
