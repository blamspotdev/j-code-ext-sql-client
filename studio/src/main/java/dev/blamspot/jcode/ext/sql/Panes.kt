package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon

/**
 * Who can reach this server, and what they are allowed to touch.
 *
 * Two lists rather than one: a role that exists and a grant that names it are different questions,
 * and the answer to "why can they read that" is usually in the second.
 */
@Composable
internal fun SecurityPane(state: StudioState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        item { Card(title = "Roles") { GridBlock(state.roles) } }
        item { Card(title = "Table privileges") { GridBlock(state.grants) } }
    }
}

/**
 * Backups and restores, run by the server.
 *
 * The path is the server's rather than this runtime's, and it says so: SQL Server writes its own
 * backups, and a path typed as if it were local is a path the server cannot write to.
 */
@Composable
internal fun BackupPane(state: StudioState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        FieldLabel("Dump file")
        Muted("A path on the server, written by BACKUP DATABASE and read by RESTORE.")
        CompactField(
            value = state.dumpPath,
            onValueChange = { state.dumpPath = it },
            placeholder = "/var/opt/mssql/backup/" + state.database + ".bak",
            modifier = Modifier.fillMaxWidth(),
            literal = true,
            monospace = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            CompactFilledButton(
                text = "Back up",
                onClick = { state.backUp() },
                enabled = !state.busy,
                busy = state.busy,
            )
            CompactOutlinedButton(
                text = "Restore",
                onClick = { state.restore() },
                enabled = !state.busy,
            )
        }
        if (state.dumpLog.isNotEmpty()) LogBlock(state.dumpLog.joinToString(Char(10).toString()))
    }
}

/**
 * The tables and what points at what.
 *
 * Laid out rather than dragged: a phone has no room for a canvas you arrange by hand, and what the
 * diagram is for — seeing which tables are joined to which — survives being tidied automatically.
 */
@Composable
internal fun DiagramPane(state: StudioState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            items(state.tables, key = { it }) { name ->
                ToggleChip(label = name, on = name in state.onCanvas) {
                    if (name in state.onCanvas) state.removeFromCanvas(name) else state.addToCanvas(name)
                }
            }
        }
        if (state.diagram.tables.isEmpty()) {
            Note("Pick the tables to draw.")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            state.diagram.tables.forEach { (name, columns) ->
                item(key = name) { DiagramTable(state, name, columns) }
            }
            if (state.diagram.edges.isNotEmpty()) {
                item {
                    Card(title = "References") {
                        state.diagram.edges.forEach { (from, to) ->
                            Muted(from + "  →  " + to)
                        }
                    }
                }
            }
        }
    }
}

/** One table on the canvas: its columns, and which of them are keys. */
@Composable
private fun DiagramTable(state: StudioState, name: String, columns: List<DiagramColumn>) {
    Card(
        title = name,
        trailing = {
            IconAction(
                icon = jcIcon(JCodeIcon.Close),
                label = "Remove from the diagram",
                onClick = { state.removeFromCanvas(name) },
                enabled = !state.busy,
            )
        },
    ) {
        columns.forEach { column ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    text = column.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Muted(column.type)
                if (column.primary) KeyTag("PK", JCodeTheme.semanticColors.success)
                if (column.foreign) KeyTag("FK", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun KeyTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    )
}

/**
 * A result set inside a card.
 *
 * Scrolled sideways rather than wrapped, for the same reason the query grid is: a row is a record,
 * and a record folded over three lines stops being one thing that can be read across.
 */
@Composable
internal fun GridBlock(grid: Grid) {
    if (!grid.ok) {
        StatusText(grid.error, isError = true)
        return
    }
    if (grid.columns.isEmpty()) {
        Muted(grid.message.ifEmpty { "Nothing to show." })
        return
    }
    val horizontal = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(horizontal)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(vertical = Space.xxs),
        ) {
            grid.columns.forEach { name -> GridCell(name, header = true) }
        }
        grid.rows.forEach { row ->
            Row(modifier = Modifier.horizontalScroll(horizontal).padding(vertical = Space.xxs)) {
                row.forEach { value -> GridCell(value) }
            }
        }
    }
}

@Composable
internal fun GridCell(value: String?, header: Boolean = false) {
    Text(
        // NULL and an empty string are different answers, and a grid that draws both as blank space
        // cannot be used to tell them apart.
        text = value ?: "NULL",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (value == null && !header) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(GridCellWidth).padding(horizontal = Space.sm),
    )
}

/** Wide enough for a timestamp, narrow enough that several columns fit on a phone. */
internal val GridCellWidth = 148.dp
