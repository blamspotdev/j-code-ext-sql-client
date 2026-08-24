package dev.blamspot.jcode.ext.sql

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One column as the diagram cares about it: its name, its type, and whether it is a key. */
internal data class DiagramColumn(
    val name: String,
    val type: String,
    val primary: Boolean,
    val foreign: Boolean,
)

/** The tables on the canvas, and the foreign keys between them. */
internal data class Diagram(
    val tables: Map<String, List<DiagramColumn>> = emptyMap(),
    val edges: List<Pair<String, String>> = emptyList(),
)

/** The studio's sections, in the order they are worked through. */
internal enum class Pane(val label: String) {
    Tables("Tables"),
    Diagram("Diagram"),
    Security("Security"),
    Backup("Backup"),
}

/** One column of a table, as the catalogue describes it. */
internal data class ColumnInfo(
    val position: String,
    val name: String,
    val type: String,
    val nullable: String,
    val default: String,
    val key: String,
)

/**
 * One database, open.
 *
 * Its own connection and its own questions: a studio tab is restored with a session and reached
 * from other tabs, so it cannot assume the drawer is on screen or that it decided anything.
 */
internal class StudioState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    view: String,
) {
    private val sqlcmd = Sqlcmd(host)

    /** The database this tab is about, carried in the route that opened it. */
    val database: String = decode(view.removePrefix("db:"))

    var conn by mutableStateOf(Conn.from(emptyMap()))
        private set

    var pane by mutableStateOf(Pane.Tables)

    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Whether the last thing asked of the server was answered. */
    var connected by mutableStateOf(false)
        private set

    var message by mutableStateOf("")
        private set
    var failed by mutableStateOf(false)
        private set

    val tables = mutableStateListOf<String>()
    var table by mutableStateOf("")
        private set

    val columns = mutableStateListOf<ColumnInfo>()

    /**
     * Every table and column in the database, for the editor to suggest from.
     *
     * Fetched once when the database opens: it is two queries, and a suggestion that has to ask the
     * server before it can offer anything arrives after the word has been typed.
     */
    private var schemaTables by mutableStateOf(emptyList<String>())
    private var schemaColumns by mutableStateOf(emptyMap<String, List<String>>())

    /** What could finish the word being typed, given this database's own names. */
    val suggestions: List<String>
        get() = suggestionsFor(query, schemaTables, schemaColumns)

    fun accept(suggestion: String) {
        query = applySuggestion(query, suggestion)
    }

    /** The query in the editor, and the last thing running it produced. */
    var query by mutableStateOf("")
    var grid by mutableStateOf(Grid())
        private set

    var confirm by mutableStateOf<Confirm?>(null)

    // --- the other three sections ---------------------------------------------------------------

    /** Who exists on this server, and what they may touch. */
    var roles by mutableStateOf(Grid())
        private set
    var grants by mutableStateOf(Grid())
        private set

    /** Where a dump is written and read — a path in the runtime, not on the server. */
    var dumpPath by mutableStateOf("")
    val dumpLog = mutableStateListOf<String>()

    /** The tables drawn on the diagram, and the keys between them. */
    val onCanvas = mutableStateListOf<String>()
    var diagram by mutableStateOf(Diagram())
        private set

    fun boot() {
        scope.launch {
            loading = true
            conn = Conn.from(host.config())
            ping()
            loadTables()
            loading = false
        }
    }

    fun reload() {
        if (busy) return
        scope.launch {
            busy = true
            conn = Conn.from(host.config())
            ping()
            loadTables()
            busy = false
        }
    }

    /** One cheap question, so the dot in the toolbar means something. */
    private suspend fun ping() {
        val (rows, error) = sqlcmd.column(conn, "SELECT 1", database)
        connected = error.isEmpty() && rows.firstOrNull() == "1"
        if (error.isNotEmpty()) report(error, failed = true)
    }

    private suspend fun loadTables() {
        val (names, error) = sqlcmd.column(conn, TABLES_SQL, database)
        tables.clear()
        if (error.isNotEmpty()) {
            report(error, failed = true)
            return
        }
        tables += names
        schemaTables = names
        loadSchemaColumns()
        if (table.isEmpty() || table !in names) table = names.firstOrNull().orEmpty()
        if (table.isNotEmpty()) loadColumns()
    }

    /** Every column in the database, keyed by the table it belongs to. */
    private suspend fun loadSchemaColumns() {
        val (rows, error) = sqlcmd.column(conn, SCHEMA_COLUMNS_SQL, database)
        if (error.isNotEmpty()) return
        val byTable = LinkedHashMap<String, MutableList<String>>()
        rows.forEach { line ->
            val table = line.substringBefore(Sh.FIELD)
            val column = line.substringAfter(Sh.FIELD, "")
            if (column.isNotEmpty()) byTable.getOrPut(table) { mutableListOf() } += column
        }
        schemaColumns = byTable.mapValues { it.value.toList() }
    }

    fun select(name: String) {
        if (name == table || busy) return
        table = name
        scope.launch {
            busy = true
            loadColumns()
            busy = false
        }
    }

    private suspend fun loadColumns() {
        val g = sqlcmd.grid(conn, columnsSql(table), database)
        columns.clear()
        if (!g.ok) {
            report(g.error, failed = true)
            return
        }
        g.rows.forEach { row ->
            columns += ColumnInfo(
                position = row.getOrNull(0).orEmpty(),
                name = row.getOrNull(1).orEmpty(),
                type = row.getOrNull(2).orEmpty(),
                nullable = row.getOrNull(3).orEmpty(),
                default = row.getOrNull(4).orEmpty(),
                key = row.getOrNull(5).orEmpty(),
            )
        }
        // The first thing anyone wants of a table they just picked is to see what is in it.
        query = "SELECT TOP 1000 * FROM " + Sh.qualified(table) + ";"
        runQuery()
    }

    fun runQuery() {
        if (busy || query.isBlank()) return
        scope.launch {
            busy = true
            val g = sqlcmd.grid(conn, query.trim().removeSuffix(";"), database, timeoutMs = 300_000L)
            grid = g
            connected = g.ok || g.error.isEmpty()
            report(if (g.ok) g.message else g.error, failed = !g.ok)
            // A statement rather than a result set may well have been a CREATE or a DROP, and the
            // table it made should be in the list without being asked for again. Only then: after a
            // SELECT there is nothing new to learn, and re-reading the catalogue per query would
            // put two more round trips on every one of them.
            if (g.ok && g.columns.isEmpty()) loadTables()
            busy = false
        }
    }

    fun newQuery() {
        query = ""
        grid = Grid()
        message = ""
        failed = false
    }

    // --- changing a table -------------------------------------------------------------------------

    fun addColumn() {
        if (table.isEmpty()) return
        confirm = Confirm(
            title = "Add column",
            body = "Add a column to " + table + ". Give it a name and a type, as SQL spells them.",
            action = "Add",
            destructive = false,
            input = "",
            placeholder = "name TEXT",
        ) { spec ->
            if (spec.isNotBlank()) {
                ddl("ALTER TABLE " + Sh.qualified(table) + " ADD COLUMN " + spec, "Column added.")
            }
        }
    }

    fun renameTable() {
        if (table.isEmpty()) return
        val bare = table.substringAfterLast('.')
        confirm = Confirm(
            title = "Rename table",
            body = "Rename " + table + ".",
            action = "Rename",
            destructive = false,
            input = bare,
            placeholder = "New name",
        ) { to ->
            if (to.isNotBlank() && to != bare) {
                ddl("ALTER TABLE " + Sh.qualified(table) + " RENAME TO " + Sh.ident(to), "Renamed to " + to + ".")
            }
        }
    }

    fun dropTable() {
        if (table.isEmpty()) return
        confirm = Confirm(
            title = "Drop table",
            body = "Drop " + table + " and everything in it? This cannot be undone.",
            action = "Drop",
        ) {
            ddl("DROP TABLE " + Sh.qualified(table), "Dropped " + table + ".")
        }
    }

    private fun ddl(sql: String, done: String) {
        if (busy) return
        scope.launch {
            busy = true
            val r = sqlcmd.statement(conn, sql, database)
            report(if (r.ok) done else r.error, failed = !r.ok)
            if (r.ok) {
                host.snackbar(done)
                loadTables()
            }
            busy = false
        }
    }

    /**
     * Open a section, fetching what it needs the first time it is asked for.
     *
     * Not on boot: the diagram and the privilege list are several joins across the whole catalogue,
     * and paying for them to open a database nobody asked to diagram is how a studio feels slow.
     */
    fun show(next: Pane) {
        if (pane == next) return
        pane = next
        when (next) {
            Pane.Security -> if (roles.columns.isEmpty()) scope.launch { work { loadSecurity() } }
            Pane.Diagram -> if (diagram.tables.isEmpty()) scope.launch { work { drawDiagram() } }
            Pane.Backup -> if (dumpPath.isEmpty()) dumpPath = "/var/opt/mssql/backup/" + database + ".bak"
            Pane.Tables -> Unit
        }
    }

    private suspend fun work(block: suspend () -> Unit) {
        busy = true
        block()
        busy = false
    }

    // --- security ---------------------------------------------------------------------------------

    private suspend fun loadSecurity() {
        roles = sqlcmd.grid(conn, ROLES_SQL, database)
        grants = sqlcmd.grid(conn, GRANTS_SQL, database)
        listOf(roles, grants).firstOrNull { !it.ok }?.let { report(it.error, failed = true) }
    }

    // --- backup and restore -----------------------------------------------------------------------

    /**
     * BACKUP DATABASE, which the server runs and writes on its own filesystem.
     *
     * Not a tool in this runtime: SQL Server writes its own backups, so the path is the server's.
     * When the server is the VM this workbench runs, the file is then pulled back to the runtime so
     * it lands somewhere reachable — a backup only the VM can see is a backup nobody can move.
     */
    fun backUp() {
        val path = dumpPath.trim()
        if (path.isBlank() || busy) return
        scope.launch {
            work {
                dumpLog.clear()
                dumpLog += "Backing up " + database + " to " + path + " on the server…"
                val r = sqlcmd.statement(
                    conn,
                    "BACKUP DATABASE " + Sh.ident(database) + " TO DISK = " + Sh.literal(path) +
                        " WITH INIT, FORMAT, STATS = 10",
                    "master",
                    timeoutMs = 900_000L,
                )
                dumpLog += if (r.ok) r.message.ifBlank { "Backup complete." } else r.error
                if (!r.ok) {
                    host.snackbar("Backup failed", "Show detail") { report(r.error, failed = true) }
                    return@work
                }
                pullFromVm(path)
                host.snackbar("Backed up to " + path)
            }
        }
    }

    /** RESTORE DATABASE, from a file the server can see. */
    fun restore() {
        val path = dumpPath.trim()
        if (path.isBlank() || busy) return
        confirm = Confirm(
            title = "Restore database",
            body = "Restore " + database + " from " + path +
                "? Everything currently in it is replaced.",
            action = "Restore",
        ) {
            scope.launch {
                work {
                    dumpLog.clear()
                    dumpLog += "Restoring " + database + " from " + path + "…"
                    pushToVm(path)
                    // Single-user first: a restore cannot proceed while anything else is connected,
                    // and the sessions in the way are not ones the user can reach from here.
                    val r = sqlcmd.statement(
                        conn,
                        "ALTER DATABASE " + Sh.ident(database) +
                            " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; " +
                            "RESTORE DATABASE " + Sh.ident(database) + " FROM DISK = " +
                            Sh.literal(path) + " WITH REPLACE, RECOVERY; " +
                            "ALTER DATABASE " + Sh.ident(database) + " SET MULTI_USER",
                        "master",
                        timeoutMs = 900_000L,
                    )
                    dumpLog += if (r.ok) r.message.ifBlank { "Restore complete." } else r.error
                    host.snackbar(if (r.ok) "Restore complete." else "Restore failed — read the log.")
                    if (r.ok) loadTables()
                }
            }
        }
    }

    /**
     * Move a backup between the runtime and the VM the server runs in.
     *
     * Only when the server is that VM: a remote SQL Server keeps its own backups where it put them,
     * and reaching into its filesystem is neither possible nor ours to do.
     */
    private suspend fun pullFromVm(path: String) {
        if (!serverIsLocalVm()) return
        if (ensureTransferTools() != null) return
        dumpLog += "Copying it out of the VM…"
        sqlcmd.shell(
            VM_SSH + " " + Sh.quote("sudo chmod 0644 " + path) + " >/dev/null 2>&1; " +
                "mkdir -p " + Sh.quote(path.substringBeforeLast('/', "/tmp")) + " && " +
                VM_SCP + " " + Sh.quote(VM_USER + "@127.0.0.1:" + path) + " " + Sh.quote(path) + " 2>&1",
            timeoutMs = 900_000L,
        )
    }

    private suspend fun pushToVm(path: String) {
        if (!serverIsLocalVm()) return
        if (ensureTransferTools() != null) return
        dumpLog += "Copying it into the VM…"
        sqlcmd.shell(
            VM_SCP + " " + Sh.quote(path) + " " + Sh.quote(VM_USER + "@127.0.0.1:" + path) + " 2>&1",
            timeoutMs = 900_000L,
        )
    }

    /** The VM this workbench runs answers on loopback; anything else is somebody else's machine. */
    private fun serverIsLocalVm(): Boolean =
        conn.host == "127.0.0.1" || conn.host == "localhost"

    private suspend fun ensureTransferTools(): String? {
        val probe = "command -v scp >/dev/null 2>&1 && command -v sshpass >/dev/null 2>&1 " +
            "&& echo OK || echo NO"
        if (sqlcmd.shell(probe, timeoutMs = 15_000L).contains("OK")) return null
        sqlcmd.shell(
            "apt-get update -y >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                "-o DPkg::Lock::Timeout=180 openssh-client sshpass >/dev/null 2>&1",
            timeoutMs = 900_000L,
        )
        return if (sqlcmd.shell(probe, timeoutMs = 15_000L).contains("OK")) null
        else "Could not install openssh-client in the runtime."
    }

    // --- diagram ----------------------------------------------------------------------------------

    fun addToCanvas(name: String) {
        if (name in onCanvas) return
        onCanvas += name
        scope.launch { work { drawDiagram() } }
    }

    fun removeFromCanvas(name: String) {
        onCanvas -= name
        scope.launch { work { drawDiagram() } }
    }

    private suspend fun drawDiagram() {
        // Only the tables actually on the canvas: every column of every table is a payload big
        // enough to stall the app on a real database, fetched for boxes nobody put there.
        if (onCanvas.isEmpty()) onCanvas += tables.take(6)
        if (onCanvas.isEmpty()) {
            diagram = Diagram()
            return
        }
        val inList = onCanvas.joinToString(",") { Sh.literal(it) }
        val cols = sqlcmd.grid(conn, diagramColumnsSql(inList), database)
        if (!cols.ok) {
            report(cols.error, failed = true)
            return
        }
        val byTable = LinkedHashMap<String, MutableList<DiagramColumn>>()
        cols.rows.forEach { row ->
            val name = row.getOrNull(0).orEmpty()
            if (name !in onCanvas) return@forEach
            byTable.getOrPut(name) { mutableListOf() } += DiagramColumn(
                name = row.getOrNull(1).orEmpty(),
                type = row.getOrNull(2).orEmpty(),
                primary = row.getOrNull(3) == "1",
                foreign = row.getOrNull(4) == "1",
            )
        }
        val keys = sqlcmd.grid(conn, diagramKeysSql(inList), database)
        val edges = keys.rows.mapNotNull { row ->
            val from = row.getOrNull(0).orEmpty()
            val to = row.getOrNull(1).orEmpty()
            if (from == to || from !in onCanvas || to !in onCanvas) null else from to to
        }
        diagram = Diagram(byTable.mapValues { it.value.toList() }, edges)
    }

    private fun report(text: String, failed: Boolean) {
        message = text
        this.failed = failed
    }

    private companion object {
        /** How the VM Manager's SQL Server VM is reached for file transfers. */
        const val VM_USER = "ubuntu"
        const val VM_OPTIONS =
            " -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"
        val VM_SSH = "sshpass -p " + VM_USER + " ssh -p 2222" + VM_OPTIONS + " " + VM_USER + "@127.0.0.1"
        val VM_SCP = "sshpass -p " + VM_USER + " scp -P 2222" + VM_OPTIONS

        const val TABLES_SQL =
            "SELECT TABLE_SCHEMA + '.' + TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_SCHEMA, TABLE_NAME"

        /** Table and column in one line, split by the same byte the grid parser uses. */
        const val SCHEMA_COLUMNS_SQL =
            "SELECT TABLE_SCHEMA + '.' + TABLE_NAME + CHAR(31) + COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.COLUMNS ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION"

        const val ROLES_SQL =
            "SELECT p.name AS principal, p.type_desc AS kind, " +
                "CASE WHEN l.name IS NULL THEN 'no' ELSE 'yes' END AS can_login, " +
                "CASE WHEN IS_SRVROLEMEMBER('sysadmin', p.name) = 1 THEN 'yes' ELSE 'no' END AS sysadmin " +
                "FROM sys.database_principals p " +
                "LEFT JOIN sys.sql_logins l ON l.name = p.name " +
                "WHERE p.type IN ('S', 'U', 'G', 'R') AND p.name NOT LIKE 'db!_%' ESCAPE '!' " +
                "ORDER BY p.name"

        const val GRANTS_SQL =
            "SELECT pr.name AS grantee, " +
                "SCHEMA_NAME(o.schema_id) + '.' + o.name AS relation, " +
                "pe.permission_name AS privilege " +
                "FROM sys.database_permissions pe " +
                "JOIN sys.database_principals pr ON pr.principal_id = pe.grantee_principal_id " +
                "JOIN sys.objects o ON o.object_id = pe.major_id " +
                "WHERE pe.class = 1 ORDER BY pr.name, relation"

        fun columnsSql(full: String): String {
            val schema = full.substringBefore('.', "dbo")
            val name = full.substringAfter('.')
            return "SELECT CAST(c.ORDINAL_POSITION AS VARCHAR(8)), c.COLUMN_NAME, " +
                "c.DATA_TYPE + COALESCE('(' + CAST(c.CHARACTER_MAXIMUM_LENGTH AS VARCHAR(8)) + ')', ''), " +
                "c.IS_NULLABLE, COALESCE(CAST(c.COLUMN_DEFAULT AS VARCHAR(128)), ''), " +
                "COALESCE(k.CONSTRAINT_TYPE, '') " +
                "FROM INFORMATION_SCHEMA.COLUMNS c " +
                "LEFT JOIN (SELECT ku.COLUMN_NAME, tc.CONSTRAINT_TYPE " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku " +
                "ON ku.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "WHERE tc.TABLE_SCHEMA = " + Sh.literal(schema) +
                " AND tc.TABLE_NAME = " + Sh.literal(name) + ") k " +
                "ON k.COLUMN_NAME = c.COLUMN_NAME " +
                "WHERE c.TABLE_SCHEMA = " + Sh.literal(schema) +
                " AND c.TABLE_NAME = " + Sh.literal(name) + " ORDER BY c.ORDINAL_POSITION"
        }

        fun diagramColumnsSql(inList: String): String =
            "SELECT c.TABLE_SCHEMA + '.' + c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, " +
                "CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END, " +
                "CASE WHEN fk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END " +
                "FROM INFORMATION_SCHEMA.COLUMNS c " +
                "LEFT JOIN (SELECT ku.TABLE_SCHEMA, ku.TABLE_NAME, ku.COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku ON ku.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "WHERE tc.CONSTRAINT_TYPE = 'PRIMARY KEY') pk " +
                "ON pk.TABLE_SCHEMA = c.TABLE_SCHEMA AND pk.TABLE_NAME = c.TABLE_NAME " +
                "AND pk.COLUMN_NAME = c.COLUMN_NAME " +
                "LEFT JOIN (SELECT ku.TABLE_SCHEMA, ku.TABLE_NAME, ku.COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku ON ku.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "WHERE tc.CONSTRAINT_TYPE = 'FOREIGN KEY') fk " +
                "ON fk.TABLE_SCHEMA = c.TABLE_SCHEMA AND fk.TABLE_NAME = c.TABLE_NAME " +
                "AND fk.COLUMN_NAME = c.COLUMN_NAME " +
                "WHERE (c.TABLE_SCHEMA + '.' + c.TABLE_NAME) IN (" + inList + ") " +
                "ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION"

        fun diagramKeysSql(inList: String): String =
            "SELECT DISTINCT SCHEMA_NAME(pt.schema_id) + '.' + pt.name, " +
                "SCHEMA_NAME(rt.schema_id) + '.' + rt.name " +
                "FROM sys.foreign_keys fk " +
                "JOIN sys.tables pt ON pt.object_id = fk.parent_object_id " +
                "JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id " +
                "WHERE (SCHEMA_NAME(pt.schema_id) + '.' + pt.name) IN (" + inList + ") " +
                "AND (SCHEMA_NAME(rt.schema_id) + '.' + rt.name) IN (" + inList + ")"
    }
}
