package com.haoai.agent.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * 自研代码高亮（1.1）：正则 tokenizer，五类 token（关键字/字符串/注释/数字/注解）。
 * 设计取舍：不引语法分析库——移动端聊天里代码块以"快速读"为主，正则分色已够用，
 * 换取零依赖与稳定性能（单次线性扫描）。未识别语言按纯文本原样输出。
 */
object CodeHighlight {

    /** 语言别名归一：围栏语言标签写法繁多，先归一再查关键字表。 */
    private val langAlias = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "py" to "python", "python3" to "python",
        "javascript" to "js", "jsx" to "js", "node" to "js",
        "typescript" to "ts", "tsx" to "ts",
        "shell" to "bash", "sh" to "bash", "zsh" to "bash", "console" to "bash",
        "yml" to "yaml",
        "html" to "xml", "svg" to "xml",
        "golang" to "go", "c++" to "cpp", "c" to "cpp"
    )

    private val keywords = mapOf(
        "kotlin" to setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var",
            "if", "else", "when", "for", "while", "do", "return", "break", "continue",
            "is", "in", "as", "this", "super", "private",
            "public", "internal", "protected", "override", "open", "abstract", "sealed",
            "data", "companion", "init", "suspend", "lateinit", "by", "try",
            "catch", "finally", "throw", "typealias", "operator", "inline", "const"
        ),
        "java" to setOf(
            "package", "import", "class", "interface", "enum", "record", "public",
            "private", "protected", "static", "final", "void", "int", "long", "double",
            "float", "boolean", "char", "byte", "short", "new", "return", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "throws", "extends", "implements", "this", "super",
            "abstract", "synchronized", "volatile", "instanceof"
        ),
        "python" to setOf(
            "def", "class", "import", "from", "as", "return", "if", "elif", "else",
            "for", "while", "break", "continue", "pass", "in", "is", "not", "and",
            "or", "try", "except", "finally", "raise", "with", "lambda", "yield",
            "global", "nonlocal", "async", "await", "del", "assert", "match", "case"
        ),
        "js" to setOf(
            "const", "let", "var", "function", "class", "return", "if", "else", "for",
            "while", "do", "break", "continue", "new", "this", "try", "catch", "finally",
            "throw", "typeof", "instanceof", "import", "export", "from", "default",
            "async", "await", "yield", "extends", "static", "get", "set", "of", "in", "delete", "void"
        ),
        "ts" to setOf(
            "const", "let", "var", "function", "class", "interface", "type", "enum",
            "return", "if", "else", "for", "while", "do", "break", "continue", "new",
            "this", "try", "catch", "finally", "throw", "typeof", "instanceof",
            "import", "export", "from", "default", "async", "await", "extends",
            "implements", "public", "private", "protected", "readonly", "static",
            "as", "keyof", "namespace", "declare", "abstract"
        ),
        "json" to setOf(),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "export", "local", "echo", "cd", "sudo",
            "apt", "apt-get", "git", "npm", "pip", "python", "curl", "wget"
        ),
        "yaml" to setOf(),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "index", "view", "join",
            "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
            "limit", "offset", "and", "or", "not", "primary", "key", "foreign",
            "references", "as", "distinct", "count", "sum", "avg", "min", "max"
        ),
        "xml" to emptySet(),
        "go" to setOf(
            "package", "import", "func", "type", "struct", "interface", "map", "chan",
            "go", "defer", "return", "if", "else", "for", "range", "switch", "case",
            "default", "break", "continue", "var", "const"
        ),
        "cpp" to setOf(
            "include", "using", "namespace", "class", "struct", "public", "private",
            "protected", "virtual", "override", "template", "typename", "const",
            "static", "void", "int", "long", "double", "float", "bool", "char", "auto",
            "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "try", "catch", "throw", "new", "delete", "this",
            "namespace"
        )
    )

    /**
     * 内置函数/类型表（对齐 highlight.js 的 built_in 类，渲染橙色）：
     * print/len/range/int/list（Python）、console/JSON（JS）、len/make（Go）等。
     * 注意 JS 的 fetch/Error 不在内——实测它们走函数名蓝（调用处染色）。
     */
    private val builtins = mapOf(
        "python" to setOf(
            "print", "len", "range", "int", "float", "str", "bool", "list", "dict",
            "set", "tuple", "sum", "min", "max", "sorted", "reversed", "enumerate",
            "zip", "map", "filter", "abs", "round", "open", "type", "isinstance",
            "hasattr", "getattr", "setattr", "input", "any", "all", "ord", "chr",
            "bytes", "bytearray", "staticmethod", "classmethod", "super"
        ),
        "js" to setOf(
            "console", "JSON", "Math", "Date", "Promise", "Array", "Object", "String",
            "Number", "Boolean", "Set", "Map", "parseInt", "parseFloat",
            "setTimeout", "setInterval", "clearTimeout", "clearInterval", "require",
            "alert", "Symbol", "Reflect", "Proxy"
        ),
        "ts" to setOf(
            "console", "JSON", "Math", "Date", "Promise", "Array", "Object", "String",
            "Number", "Boolean", "Set", "Map", "fetch", "parseInt", "parseFloat",
            "setTimeout", "setInterval", "Partial", "Readonly", "Record", "Pick", "Omit"
        ),
        "java" to setOf(
            "System", "String", "Math", "Integer", "Long", "Double", "Float", "Boolean",
            "Character", "Byte", "Short", "StringBuilder", "StringBuffer", "List",
            "Map", "Set", "ArrayList", "HashMap", "HashSet", "Optional", "Objects"
        ),
        "kotlin" to setOf(
            "println", "print", "listOf", "mapOf", "setOf", "mutableListOf", "mutableMapOf",
            "mutableSetOf", "arrayOf", "intArrayOf", "emptyList", "emptyMap", "emptySet",
            "requireNotNull", "checkNotNull", "lazy", "run", "let", "also", "apply",
            "with", "takeIf", "takeUnless", "repeat", "String", "Int", "Long", "Double",
            "Float", "Boolean", "Char", "Byte", "Short", "Any", "Unit", "Pair", "Triple"
        ),
        "go" to setOf(
            "len", "cap", "make", "new", "append", "copy", "delete", "panic",
            "recover", "print", "println", "close", "complex", "real", "imag", "min", "max",
            "string", "int", "int8", "int16", "int32", "int64", "uint", "float32", "float64",
            "bool", "byte", "rune", "error"
        ),
        "bash" to setOf(
            "cat", "ls", "grep", "sed", "awk", "find", "sort", "uniq", "head", "tail",
            "wc", "chmod", "chown", "mkdir", "rmdir", "rm", "cp", "mv", "touch",
            "systemctl", "journalctl", "docker", "kubectl", "ssh", "scp", "tar", "zip", "unzip"
        ),
        "sql" to setOf(
            "count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast", "now",
            "date", "string_agg", "row_number", "rank", "round", "abs", "lower", "upper"
        ),
        "cpp" to setOf(
            "std", "cout", "cin", "endl", "vector", "string", "map", "set", "pair",
            "make_pair", "make_shared", "make_unique", "sort", "find", "size_t", "uint32_t", "uint64_t"
        )
    )

    /** 高亮配色：跟随深浅色主题由调用方传入（MarkdownText 按当前主题取色）。 */
    data class Colors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color,
        val plain: Color,
        /** v2.2：内置函数/类型（print/len/int/console，对齐 highlight.js built_in #C18401）。 */
        val builtin: Color = Color.Unspecified,
        /** v2.2：函数名（对齐 highlight.js title.function，蓝）。 */
        val function: Color = Color.Unspecified,
        /** v2.3：字面量 true/false/None/null（AtomOne literal，浅色蓝/深色紫）。 */
        val literal: Color = Color.Unspecified
    )

    /** 深色主题配色（AtomOne Dark 官方值）。 */
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

    /**
     * 函数名染蓝规则（对齐 highlight.js 各语言语法实测）：
     * - js/ts：调用处 `ident(` 蓝（fetch(/json(/Error( 像素实测蓝）+ `const NAME = (…) =>` 定义蓝；
     * - python：仅 def/class 后的名字蓝（reduce( 调用处实测黑）；kotlin/go/java/cpp 同为仅定义处。
     */
    private val defPrevWords = mapOf(
        "python" to setOf("def", "class"),
        "kotlin" to setOf("fun", "class", "interface", "object"),
        "java" to setOf("class", "interface", "enum", "record", "new", "void"),
        "go" to setOf("func", "type"),
        "cpp" to setOf("class", "struct", "new"),
        "js" to setOf("function", "class"),
        "ts" to setOf("function", "class")
    )

    /** 调用处 `ident(` 染蓝的语言：hljs 11 仅 js/ts 语法带 lookahead 调用规则。 */
    private val callSiteBlue = setOf("js", "ts")

    private val tokenRegex = Regex(
        "//[^\n]*" +                                  // 行注释（c 系）
            "|#[^\n]*" +                              // # 注释（python/bash/yaml）
            "|-- [^\n]*" +                            // sql 行注释
            "\"\"\"[\\s\\S]*?\"\"\"" +                // python 三引号 docstring（字符串绿）
            "|\"(?:\\\\.|[^\"\\\\\n])*\"" +           // 双引号字符串
            "|'(?:\\\\.|[^'\\\\\n])*'" +              // 单引号字符串
            "|`(?:\\\\.|[^`\\\\\n])*`" +              // js 模板字符串（同行闭合）
            "|@\\w+" +                                // 注解/装饰器
            "|\\b0x[0-9a-fA-F]+\\b" +                 // 十六进制
            "|\\b\\d+(?:\\.\\d+)?(?:[fFlLdDmM]|\\.\\.\\.)?\\b" + // 数字
            "|\\b\\w+\\b"                             // 标识符（再筛关键字）
    )

    /** 字面量表（AtomOne literal 类：浅色蓝/深色紫，与关键字紫区分——hljs 官方行为）。 */
    private val literals = mapOf(
        "python" to setOf("True", "False", "None"),
        "js" to setOf("true", "false", "null", "undefined", "NaN", "Infinity"),
        "ts" to setOf("true", "false", "null", "undefined", "NaN", "Infinity"),
        "json" to setOf("true", "false", "null"),
        "java" to setOf("true", "false", "null"),
        "kotlin" to setOf("true", "false", "null"),
        "go" to setOf("true", "false", "nil", "iota"),
        "cpp" to setOf("true", "false", "nullptr"),
        "sql" to setOf("null"),
        "yaml" to setOf("true", "false", "null", "yes", "no")
    )

    // 高亮结果 LRU（v0.18.1 优化⑤）：Colors 为 data class 可作 key；仅闭合代码块
    // 走缓存（调用方控制），流式分片不进来防污染。pop 重建聊天页时历史代码块直接命中。
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

    /** 生成高亮 AnnotatedString；lang 不在支持表内时返回纯文本色。 */
    fun highlight(code: String, langRaw: String, colors: Colors): AnnotatedString {
        val lang = langAlias[langRaw.lowercase()] ?: langRaw.lowercase()
        val kw = keywords[lang]
        val bi = builtins[lang]
        val lit = literals[lang]
        val defPrev = defPrevWords[lang]
        val jsLike = lang in callSiteBlue
        val annotated = buildAnnotatedString {
            var index = 0
            var prevWord = ""
            for (m in tokenRegex.findAll(code)) {
                if (m.range.first > index) {
                    appendStyle(code.substring(index, m.range.first), colors.plain, null)
                }
                val token = m.value
                val nextCh = code.getOrNull(m.range.last + 1)
                val isWord = token.firstOrNull()?.isLetterOrDigit() == true || token.firstOrNull() == '_'
                val (color, weight) = when {
                    // 注释优先（避免 // 被当除号、# 被当普通符号）
                    token.startsWith("//") || token.startsWith("#") || token.startsWith("-- ") ->
                        colors.comment to null
                    // 字符串（含 """docstring"""、`模板串`，实测均绿）
                    token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") ->
                        colors.string to null
                    token.startsWith("@") -> colors.annotation to null
                    token.firstOrNull()?.isDigit() == true -> colors.number to null
                    kw != null && token in kw -> colors.keyword to null
                    lit != null && token in lit -> colors.literal to null
                    // 内置函数/类型（赭黄），优先于函数名（print( 仍内置色）
                    bi != null && token in bi -> colors.builtin to null
                    // 函数名蓝：定义处（def fib/fun main/func main…）或 js/ts 调用处/箭头定义
                    defPrev != null && prevWord in defPrev -> colors.function to null
                    jsLike && (nextCh == '(' || isArrowFnDef(code, m.range.last + 1)) ->
                        colors.function to null
                    else -> colors.plain to null
                }
                appendStyle(token, color, weight)
                if (isWord) prevWord = token
                index = m.range.last + 1
            }
            if (index < code.length) appendStyle(code.substring(index), colors.plain, null)
        }
        return annotated
    }

    /** js/ts：token 之后是否为 `= [async] (…) =>`（const fetchJson = async (url) =>… 定义名蓝）。 */
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

    private fun AnnotatedString.Builder.appendStyle(text: String, color: Color, weight: FontWeight?) {
        pushStyle(SpanStyle(color = color, fontWeight = weight))
        append(text)
        pop()
    }
}
