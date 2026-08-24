package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor

/**
 * The query editor.
 *
 * Its own field rather than the shared one: SQL wants colouring, and colouring means a visual
 * transformation the plain field has no way to take. Everything else about it — the radius, the
 * hairline border, the surface tint — is the app's, so it does not read as a foreign control.
 */
@Composable
internal fun SqlField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Radius.xl),
        color = colors.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(StrokeWidth.thin, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.copy(
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
            ),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = SqlHighlight(colors.primary, JCodeTheme.semanticColors.success),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp, max = 160.dp)
                .padding(horizontal = Space.ms, vertical = Space.sm),
        )
    }
}

/**
 * Keywords, strings and numbers, coloured in place.
 *
 * An identity offset mapping, because nothing is inserted or removed — only painted. A transformation
 * that changed the text would put the caret somewhere other than where it was typed.
 */
private class SqlHighlight(
    private val keyword: Color,
    private val literal: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, keyword, literal), OffsetMapping.Identity)
}

/** The colouring itself, split out so it can be reasoned about without a field around it. */
internal fun highlight(sql: String, keyword: Color, literal: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                // A quoted string runs to its closing quote, doubled quotes included, and nothing
                // inside it is a keyword however much it looks like one.
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') i++ else break
                        }
                        i++
                    }
                    i = (i + 1).coerceAtMost(sql.length)
                    withStyle(SpanStyle(color = literal)) { append(sql.substring(start, i)) }
                }

                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    val end = sql.indexOf(Char(10), i).let { if (it < 0) sql.length else it }
                    withStyle(SpanStyle(color = literal.copy(alpha = 0.6f))) {
                        append(sql.substring(i, end))
                    }
                    i = end
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    val word = sql.substring(start, i)
                    if (word.uppercase() in KEYWORDS) {
                        withStyle(SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold)) {
                            append(word)
                        }
                    } else {
                        append(word)
                    }
                }

                c.isDigit() -> {
                    val start = i
                    while (i < sql.length && (sql[i].isDigit() || sql[i] == '.')) i++
                    withStyle(SpanStyle(color = literal)) { append(sql.substring(start, i)) }
                }

                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

/**
 * What could be typed next.
 *
 * A row of chips rather than a popup anchored to the caret: a popup over the caret on a phone sits
 * under the thumb that is typing, and the keyboard is already where the eye is.
 */
@Composable
internal fun Suggestions(items: List<String>, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items(items, key = { it }) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onPick(suggestion) }
                    .handCursor()
                    .padding(horizontal = Space.sm, vertical = Space.xxs),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The word being typed, and what in the schema could finish it.
 *
 * Tables and their columns, plus the keywords: the point of a schema-aware suggestion is that it
 * knows the names in *this* database, which is exactly what cannot be guessed.
 */
internal fun suggestionsFor(
    text: String,
    tables: List<String>,
    columnsByTable: Map<String, List<String>>,
): List<String> {
    val word = currentWord(text)
    if (word.length < 2) return emptyList()
    val lower = word.lowercase()
    // Columns of the tables this statement already names come first: having written FROM orders,
    // the next word is far more likely to be one of its columns than any other name on the server.
    val named = tables.filter { text.contains(it, ignoreCase = true) }
    val columns = named.flatMap { columnsByTable[it].orEmpty() }
    return (columns + tables + KEYWORDS)
        .filter { it.lowercase().startsWith(lower) && !it.equals(word, ignoreCase = true) }
        .distinct()
        .take(12)
}

/** Replace the word being typed with the one chosen. */
internal fun applySuggestion(text: String, suggestion: String): String {
    val word = currentWord(text)
    return text.dropLast(word.length) + suggestion + " "
}

private fun currentWord(text: String): String =
    text.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
    "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
    "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "AS", "AND", "OR", "NOT",
    "NULL", "IS", "IN", "LIKE", "ILIKE", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "END", "UNION",
    "ALL", "EXISTS", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "UNIQUE", "CHECK",
    "CONSTRAINT", "CASCADE", "RETURNING", "WITH", "GRANT", "REVOKE", "TRUNCATE", "VACUUM", "ANALYZE",
    "EXPLAIN", "BEGIN", "COMMIT", "ROLLBACK", "COALESCE", "COUNT", "SUM", "AVG", "MIN", "MAX",
)
