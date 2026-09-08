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
            "is", "in", "as", "null", "true", "false", "this", "super", "private",
            "public", "internal", "protected", "override", "open", "abstract", "sealed",
            "data", "companion", "init", "suspend", "lateinit", "by", "lazy", "try",
            "catch", "finally", "throw", "typealias", "operator", "inline", "const"
        ),
        "java" to setOf(
            "package", "import", "class", "interface", "enum", "record", "public",
            "private", "protected", "static", "final", "void", "int", "long", "double",
            "float", "boolean", "char", "byte", "short", "new", "return", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "throws", "extends", "implements", "this", "super",
            "null", "true", "false", "abstract", "synchronized", "volatile", "instanceof"
        ),
        "python" to setOf(
            "def", "class", "import", "from", "as", "return", "if", "elif", "else",
            "for", "while", "break", "continue", "pass", "in", "is", "not", "and",
            "or", "None", "True", "False", "try", "except", "finally", "raise",
            "with", "lambda", "yield", "global", "nonlocal", "self", "async", "await",
            "print", "del", "assert"
        ),
        "js" to setOf(
            "const", "let", "var", "function", "class", "return", "if", "else", "for",
            "while", "do", "break", "continue", "new", "this", "null", "undefined",
            "true", "false", "try", "catch", "finally", "throw", "typeof", "instanceof",
            "import", "export", "from", "default", "async", "await", "yield", "extends",
            "static", "get", "set", "of", "in"
        ),
        "ts" to setOf(
            "const", "let", "var", "function", "class", "interface", "type", "enum",
            "return", "if", "else", "for", "while", "do", "break", "continue", "new",
            "this", "null", "undefined", "true", "false", "try", "catch", "finally",
            "throw", "typeof", "instanceof", "import", "export", "from", "default",
            "async", "await", "extends", "implements", "public", "private", "protected",
            "readonly", "static", "as", "keyof", "namespace", "declare", "abstract"
        ),
        "json" to setOf("true", "false", "null"),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "export", "local", "echo", "cd", "sudo",
            "apt", "apt-get", "git", "npm", "pip", "python", "curl", "wget"
        ),
        "yaml" to setOf("true", "false", "null", "yes", "no"),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "index", "view", "join",
            "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
            "limit", "offset", "and", "or", "not", "null", "primary", "key", "foreign",
            "references", "as", "distinct", "count", "sum", "avg", "min", "max"
        ),
        "xml" to emptySet(),
        "go" to setOf(
            "package", "import", "func", "type", "struct", "interface", "map", "chan",
            "go", "defer", "return", "if", "else", "for", "range", "switch", "case",
            "default", "break", "continue", "var", "const", "nil", "true", "false"
        ),
        "cpp" to setOf(
            "include", "using", "namespace", "class", "struct", "public", "private",
            "protected", "virtual", "override", "template", "typename", "const",
            "static", "void", "int", "long", "double", "float", "bool", "char", "auto",
            "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "try", "catch", "throw", "new", "delete", "this", "nullptr",
            "true", "false", "namespace", "std"
        )
    )

    /** 高亮配色：跟随深浅色主题由调用方传入（MarkdownText 按当前主题取色）。 */
    data class Colors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color,
        val plain: Color
    )

    /** 深色主题配色（AtomOne Dark，与浅色同一体系）。 */
    fun darkColors() = Colors(
        keyword = Color(0xFFC678DD),
        string = Color(0xFF98C379),
        comment = Color(0xFF5C6370),
        number = Color(0xFFD19A66),
        annotation = Color(0xFF61AFEF),
        plain = Color(0xFFABB2BF)
    )

    /** 浅色主题配色（AtomOne Light，对齐 上游 代码块观感：紫关键字/绿字符串/橙数字/蓝函数名）。 */
    fun lightColors() = Colors(
        keyword = Color(0xFFA626A4),
        string = Color(0xFF50A14F),
        comment = Color(0xFFA0A1A7),
        number = Color(0xFF986801),
        annotation = Color(0xFF4078F2),
        plain = Color(0xFF383A42)
    )

    private val tokenRegex = Regex(
        "//[^\n]*" +                                  // 行注释（c 系）
            "|#[^\n]*" +                              // # 注释（python/bash/yaml）
            "|-- [^\n]*" +                            // sql 行注释
            "\"\"\"[\\s\\S]*?\"\"\"" +                // python 三引号
            "|\"(?:\\\\.|[^\"\\\\\n])*\"" +           // 双引号字符串
            "|'(?:\\\\.|[^'\\\\\n])*'" +              // 单引号字符串
            "|@\\w+" +                                // 注解/装饰器
            "|\\b0x[0-9a-fA-F]+\\b" +                 // 十六进制
            "|\\b\\d+(?:\\.\\d+)?(?:[fFlLdDmM]|\\.\\.\\.)?\\b" + // 数字
            "|\\b\\w+\\b"                             // 标识符（再筛关键字）
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
        val annotated = buildAnnotatedString {
            var index = 0
            for (m in tokenRegex.findAll(code)) {
                if (m.range.first > index) {
                    appendStyle(code.substring(index, m.range.first), colors.plain, null)
                }
                val token = m.value
                val (color, weight) = when {
                    // 注释优先（避免 // 被当除号、# 被当普通符号）
                    token.startsWith("//") || token.startsWith("#") ||
                        token.startsWith("-- ") || token.startsWith("\"\"\"") ->
                        colors.comment to null
                    token.startsWith("\"") || token.startsWith("'") -> colors.string to null
                    token.startsWith("@") -> colors.annotation to FontWeight.Medium
                    token.firstOrNull()?.isDigit() == true -> colors.number to null
                    kw != null && token in kw -> colors.keyword to FontWeight.Medium
                    else -> colors.plain to null
                }
                appendStyle(token, color, weight)
                index = m.range.last + 1
            }
            if (index < code.length) appendStyle(code.substring(index), colors.plain, null)
        }
        return annotated
    }

    private fun AnnotatedString.Builder.appendStyle(text: String, color: Color, weight: FontWeight?) {
        pushStyle(SpanStyle(color = color, fontWeight = weight))
        append(text)
        pop()
    }
}
