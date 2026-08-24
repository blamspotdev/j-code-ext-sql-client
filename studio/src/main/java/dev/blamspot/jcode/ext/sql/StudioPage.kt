package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * One database, open in its own tab.
 *
 * The toolbar says which database and whether the server is answering; everything below it is the
 * section being worked in. The sections are tabs rather than pages because they are four views of
 * one database, and going between them should not feel like navigating away from it.
 */
@Composable
internal fun StudioPage(state: StudioState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        StudioBar(state)
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        if (state.message.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                StatusText(state.message, state.failed)
            }
        }
        when {
            state.loading -> Note("Opening " + state.database + "…", spinner = true)
            state.pane == Pane.Tables -> TablesPane(state)
            state.pane == Pane.Diagram -> DiagramPane(state, Modifier.weight(1f))
            state.pane == Pane.Security -> SecurityPane(state, Modifier.weight(1f))
            else -> BackupPane(state, Modifier.weight(1f))
        }
    }
    state.confirm?.let { c -> ConfirmDialog(c) { state.confirm = null } }
}

/** Which database, whether it is answering, and the sections of it. */
@Composable
private fun StudioBar(state: StudioState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(Space.sm)
                    .clip(CircleShape)
                    .background(
                        if (state.connected) JCodeTheme.semanticColors.success
                        else MaterialTheme.colorScheme.error,
                    ),
            )
            Text(
                text = state.database,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(modifier = Modifier.weight(1f))
            IconAction(
                icon = jcIcon(JCodeIcon.Refresh),
                label = "Reconnect",
                onClick = { state.reload() },
                enabled = !state.busy,
            )
        }
        // Its own row: four sections and a toolbar do not fit beside a database name on a phone,
        // and the name is the one part that must not be clipped.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pane.entries.forEach { p ->
                ToggleChip(label = p.label, on = state.pane == p) { state.show(p) }
            }
        }
    }
}

/** Tables, their columns, and whatever the query in the editor last returned. */
@Composable
private fun TablesPane(state: StudioState) {
    Column(modifier = Modifier.fillMaxSize()) {
        TableStrip(state)
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactFilledButton(
                text = "Run",
                onClick = { state.runQuery() },
                enabled = !state.busy,
                busy = state.busy,
            )
            CompactOutlinedButton(text = "New query", onClick = { state.newQuery() }, enabled = !state.busy)
            Box(modifier = Modifier.weight(1f))
            CompactOutlinedButton(text = "Add column", onClick = { state.addColumn() }, enabled = !state.busy)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
            SqlField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Suggestions(state.suggestions) { state.accept(it) }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = Space.sm),
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ResultGrid(state.grid, modifier = Modifier.weight(1f))
    }
}

/** The tables in this database, and the things that can be done to the one selected. */
@Composable
private fun TableStrip(state: StudioState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            items(state.tables, key = { it }) { name ->
                ToggleChip(label = name, on = state.table == name) { state.select(name) }
            }
        }
        if (state.table.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Muted(state.columns.size.toString() + " columns")
                Box(modifier = Modifier.weight(1f))
                IconAction(
                    icon = jcIcon(JCodeIcon.Rename),
                    label = "Rename table",
                    onClick = { state.renameTable() },
                    enabled = !state.busy,
                )
                IconAction(
                    icon = jcIcon(JCodeIcon.Delete),
                    label = "Drop table",
                    onClick = { state.dropTable() },
                    enabled = !state.busy,
                        )
            }
        }
    }
}

/**
 * What the server sent back.
 *
 * Scrolled in both directions rather than wrapped: a row is a record, and a record folded over three
 * lines stops being one thing you can read across.
 */
@Composable
private fun ResultGrid(grid: Grid, modifier: Modifier = Modifier) {
    if (grid.columns.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(Space.lg)) {
            Muted(grid.message.ifEmpty { "No rows." })
        }
        return
    }
    val horizontal = rememberScrollState()
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .horizontalScroll(horizontal)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(vertical = Space.xxs),
            ) {
                grid.columns.forEach { name -> GridCell(name, header = true) }
            }
        }
        items(grid.rows.size) { i ->
            Row(modifier = Modifier.horizontalScroll(horizontal).padding(vertical = Space.xxs)) {
                grid.rows[i].forEach { value -> GridCell(value) }
            }
        }
    }
}

