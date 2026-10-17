package com.pmcl.ui.studio

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.util.Locale

internal enum class Syntax { JSON, PROPS, TOML, LOG, PLAIN }

internal fun syntaxOf(name: String): Syntax {
    val lower = name.lowercase(Locale.ROOT)
    return when (lower.substringAfterLast('.', "")) {
        "json", "json5", "mcmeta" -> Syntax.JSON
        "properties", "cfg", "conf", "ini", "txt", "yml", "yaml", "lang" -> Syntax.PROPS
        "toml" -> Syntax.TOML
        "log" -> Syntax.LOG
        "java", "kt", "kts", "gradle", "groovy" -> Syntax.PROPS
        else -> Syntax.PLAIN
    }
}

private val stringRe = Regex(""""(?:[^"\\\n]|\\.)*"""")
private val numberRe = Regex("""(?<![\w.])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[dDfFlL]?(?![\w.])""")
private val constRe = Regex("""\b(?:true|false|null|on|off|yes|no)\b""", RegexOption.IGNORE_CASE)
private val jsonKeyRe = Regex(""""(?:[^"\\\n]|\\.)*"(?=\s*:)""")
private val propKeyRe = Regex("""(?m)^[ \t]*([^#;=:\s\[][^=:\n]*?)[ \t]*(?=[=:])""")
private val sectionRe = Regex("""(?m)^[ \t]*\[[^\]\n]*\][ \t]*$""")
private val hashCommentRe = Regex("""(?m)^[ \t]*[#;!].*$""")
private val lineCommentRe = Regex("""(?m)//.*$""")
private val tomlCommentRe = Regex("""(?m)#.*$""")
private val logLevelRe = Regex("""\b(?:ERROR|FATAL|WARN|INFO|DEBUG)\b""")
private val logTimeRe = Regex("""^\[[0-9:.\s]+\]""")

/** 超过这个长度就不做高亮，避免大文件卡顿。 */
internal const val MAX_HIGHLIGHT = 80_000

internal fun highlight(text: String, syntax: Syntax, p: StudioPalette): AnnotatedString {
    if (syntax == Syntax.PLAIN || text.length > MAX_HIGHLIGHT) return AnnotatedString(text)
    val b = AnnotatedString.Builder(text)
    fun paint(re: Regex, style: SpanStyle, group: Int = 0) {
        re.findAll(text).forEach { m ->
            val r = if (group == 0) m.range else m.groups[group]?.range ?: return@forEach
            if (!r.isEmpty()) b.addStyle(style, r.first, r.last + 1)
        }
    }
    val key = SpanStyle(color = p.synKey)
    val str = SpanStyle(color = p.synString)
    val num = SpanStyle(color = p.synNumber)
    val const = SpanStyle(color = p.synConst, fontWeight = FontWeight.Medium)
    val comment = SpanStyle(color = p.synComment, fontStyle = FontStyle.Italic)
    val section = SpanStyle(color = p.synSection, fontWeight = FontWeight.SemiBold)
    when (syntax) {
        Syntax.JSON -> {
            paint(numberRe, num)
            paint(constRe, const)
            paint(stringRe, str)
            paint(jsonKeyRe, key)
            paint(lineCommentRe, comment)
        }
        Syntax.PROPS, Syntax.TOML -> {
            paint(numberRe, num)
            paint(constRe, const)
            paint(stringRe, str)
            paint(propKeyRe, key, 1)
            paint(sectionRe, section)
            paint(if (syntax == Syntax.TOML) tomlCommentRe else hashCommentRe, comment)
        }
        Syntax.LOG -> {
            paint(logTimeRe, SpanStyle(color = p.muted))
            logLevelRe.findAll(text).forEach { m ->
                val color = when (m.value) {
                    "ERROR", "FATAL" -> p.error
                    "WARN" -> p.warn
                    "INFO" -> p.ok
                    else -> p.muted
                }
                b.addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
            }
        }
        Syntax.PLAIN -> Unit
    }
    return b.toAnnotatedString()
}

internal class SyntaxTransformation(private val syntax: Syntax, private val p: StudioPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, syntax, p), OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean = other is SyntaxTransformation && other.syntax == syntax && other.p == p
    override fun hashCode(): Int = syntax.hashCode() * 31 + p.hashCode()
}
