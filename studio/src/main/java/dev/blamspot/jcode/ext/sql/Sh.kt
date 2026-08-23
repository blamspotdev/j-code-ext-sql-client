package dev.blamspot.jcode.ext.sql

/**
 * Shell quoting, T-SQL quoting, and the byte sqlcmd is asked to delimit its columns with.
 *
 * Spelled with character codes rather than escapes: these end up inside shell strings that are
 * themselves inside Kotlin strings, and every layer of quoting is a layer to get wrong.
 */
internal object Sh {

    /** One shell argument, safe whatever is in it — a password with a quote in it included. */
    fun quote(value: String): String = "'" + value.replace("'", ESCAPED_QUOTE) + "'"

    private val ESCAPED_QUOTE = "'" + Char(92) + "''"

    /**
     * A T-SQL identifier, always bracketed.
     *
     * Always, not only when it needs to be: a name that looks safe today is a reserved word in the
     * next version, and a bracket inside one is doubled rather than trusted.
     */
    fun ident(name: String): String = OPEN + name.replace(CLOSE, CLOSE + CLOSE) + CLOSE

    /** A schema-qualified name, each part bracketed on its own. */
    fun qualified(name: String): String =
        name.split('.').filter { it.isNotEmpty() }.joinToString(".") { ident(it) }

    /** A T-SQL string literal. */
    fun literal(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * The column separator.
     *
     * A control byte rather than a comma or a pipe: either can appear in a value, and sqlcmd's
     * output has no quoting to tell a delimiter from the data.
     */
    val FIELD: Char = Char(0x1F)

    private val OPEN = Char(91).toString()
    private val CLOSE = Char(93).toString()
}
