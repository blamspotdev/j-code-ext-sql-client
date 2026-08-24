package dev.blamspot.jcode.ext.sql

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One database on the server, and how much of the disk it is using. */
internal data class Database(val name: String, val size: String)

/**
 * The drawer: which server, and what is on it.
 *
 * Everything the panel knows it asked the server for, because a database is not a file that can be
 * watched — the only way to know what is there is to ask, and the only honest moment to ask is when
 * something changed or the user said to.
 */
internal class PanelState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
) {
    private val sqlcmd = Sqlcmd(host)

    var conn by mutableStateOf(Conn.from(emptyMap()))
        private set

    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The last thing that went wrong, shown in place rather than thrown away. */
    var error by mutableStateOf("")
        private set

    /** Whether the sqlcmd client is in the runtime at all — nothing works without it. */
    var clientInstalled by mutableStateOf(true)
        private set

    /** What the install script has printed so far, when one is running. */
    val log = mutableStateListOf<String>()

    val databases = mutableStateListOf<Database>()

    /** The question on screen, if one is. */
    var confirm by mutableStateOf<Confirm?>(null)

    fun boot() {
        scope.launch {
            loading = true
            refresh()
            loading = false
        }
    }

    fun reload() {
        if (busy) return
        scope.launch {
            busy = true
            refresh()
            busy = false
        }
    }

    private suspend fun refresh() {
        conn = Conn.from(host.config())
        clientInstalled = sqlcmd.shell(
            "command -v sqlcmd >/dev/null 2>&1 && echo OK || echo NO",
            timeoutMs = 15_000L,
        ).contains("OK")
        if (clientInstalled && conn.password.isNotEmpty()) listDatabases() else databases.clear()
    }

    // --- the server, and what is on it ----------------------------------------------------------

    private suspend fun listDatabases() {
        val g = sqlcmd.grid(conn, DATABASES_SQL)
        error = g.error
        databases.clear()
        if (!g.ok) return
        g.rows.forEach { row ->
            val name = row.getOrNull(0).orEmpty()
            if (name.isNotEmpty()) databases += Database(name, row.getOrNull(1).orEmpty())
        }
    }

    /** Open a database in its own tab. A studio is a sitting, not a glance past the drawer. */
    fun open(database: String) = host.openView("db:" + encode(database), title = database)

    fun createDatabase() {
        confirm = Confirm(
            title = "New database",
            body = "Create a database on " + conn.server + ".",
            action = "Create",
            destructive = false,
            input = "",
            placeholder = "Database name",
        ) { name ->
            if (name.isNotBlank()) ddl("CREATE DATABASE " + Sh.ident(name), "Created " + name + ".")
        }
    }

    fun renameDatabase(name: String) {
        confirm = Confirm(
            title = "Rename database",
            body = "Rename " + name + ".",
            action = "Rename",
            destructive = false,
            input = name,
            placeholder = "New name",
        ) { to ->
            if (to.isNotBlank() && to != name) {
                ddl(
                    "ALTER DATABASE " + Sh.ident(name) + " MODIFY NAME = " + Sh.ident(to),
                    "Renamed to " + to + ".",
                )
            }
        }
    }

    fun dropDatabase(name: String) {
        confirm = Confirm(
            title = "Drop database",
            body = "Drop " + name + " and everything in it? This cannot be undone.",
            action = "Drop",
        ) {
            // Single-user first, so an idle connection someone left open does not make this fail
            // with a message about other sessions the user has no way to act on from here.
            ddl(
                "ALTER DATABASE " + Sh.ident(name) +
                    " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE " + Sh.ident(name),
                "Dropped " + name + ".",
            )
        }
    }

    private fun ddl(sql: String, done: String) {
        if (busy) return
        scope.launch {
            busy = true
            // Against master: a database cannot be created, renamed or dropped from a session
            // connected to it.
            val r = sqlcmd.statement(conn, sql, "master")
            if (r.ok) host.snackbar(done) else host.snackbar("Failed", "Show detail") { error = r.error }
            error = r.error
            refresh()
            busy = false
        }
    }

    // --- the client ------------------------------------------------------------------------------

    /**
     * Install go-sqlcmd, the standalone arm64 client.
     *
     * From the release tarball rather than apt: the Microsoft apt repository has no sqlcmd package
     * for several Ubuntu releases, so the package route works on some runtimes and not others.
     */
    fun installClient() {
        if (busy) return
        scope.launch {
            busy = true
            log.clear()
            error = ""
            say("Installing the sqlcmd (go-sqlcmd) arm64 client…")
            val printed = sqlcmd.shell(SCRIPT, timeoutMs = 900_000L)
            printed.lines().filter { it.isNotBlank() }.takeLast(6).forEach { say(it) }
            refresh()
            if (clientInstalled) host.snackbar("sqlcmd installed.")
            busy = false
        }
    }

    private fun say(line: String) {
        log += line
    }

    private companion object {
        /** Name and size in one pass, so the two are from the same moment. */
        const val DATABASES_SQL =
            "SELECT d.name, CAST(CAST(SUM(f.size) * 8.0 / 1024 AS DECIMAL(18,1)) AS VARCHAR(32)) + ' MB' " +
                "FROM sys.databases d JOIN sys.master_files f ON f.database_id = d.database_id " +
                "GROUP BY d.name ORDER BY d.name"

        /** A literal dollar: the shell expands these, and Kotlin must not read them as templates. */
        val D = Char(36).toString()

        /** A double quote, for the shell's own quoting inside these lines. */
        val Q = Char(34).toString()

        const val URL =
            "https://github.com/microsoft/go-sqlcmd/releases/latest/download/sqlcmd-linux-arm64.tar.bz2"

        val SCRIPT = listOf(
            "set -e",
            "export DEBIAN_FRONTEND=noninteractive",
            "if ! command -v curl >/dev/null 2>&1 || ! command -v bzip2 >/dev/null 2>&1; then",
            "  apt-get update -y >/dev/null 2>&1 || true",
            "  apt-get install -y -o DPkg::Lock::Timeout=180 curl ca-certificates bzip2 tar 2>&1 | tail -2",
            "fi",
            "tmp=" + D + "(mktemp -d)",
            "curl -fsSL " + URL + " -o " + Q + D + "tmp/sqlcmd.tar.bz2" + Q,
            "tar -xjf " + Q + D + "tmp/sqlcmd.tar.bz2" + Q + " -C " + Q + D + "tmp" + Q,
            "bin=" + D + "(find " + Q + D + "tmp" + Q + " -type f -name sqlcmd | head -1)",
            "install -m 0755 " + Q + D + "bin" + Q + " /usr/local/bin/sqlcmd",
            "rm -rf " + Q + D + "tmp" + Q,
            "sqlcmd --version 2>&1 | head -2",
        ).joinToString(Char(10).toString())

    }
}

/** A database name as a view id can carry it. */
internal fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

internal fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
