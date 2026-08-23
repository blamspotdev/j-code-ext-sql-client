package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
 * The Postgres drawer: which server, what is on it, and the server this runtime can be itself.
 *
 * Drawn from JCode's own parts so a database reads the way a file does in the Explorer — the same
 * spacing scale, the same compact buttons, the same semantic colours.
 */
@Composable
internal fun SqlPanel(state: PanelState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelHeader(state)
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        when {
            state.loading -> Note("Looking for a server…", spinner = true)
            !state.clientInstalled -> MissingClient(state)
            else -> Body(state)
        }
    }
    state.confirm?.let { c -> ConfirmDialog(c) { state.confirm = null } }
}

/** Which server, how it is reached, and the way to ask it again. */
@Composable
private fun PanelHeader(state: PanelState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.conn.server,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The reach is worth saying on every screen: the same host means a different machine
            // depending on it, and a tunnel that is not up looks exactly like a server that is down.
            Muted(state.conn.reach.label + " · " + state.conn.user)
        }
        IconAction(
            icon = jcIcon(JCodeIcon.Refresh),
            label = "Refresh",
            onClick = { state.reload() },
            enabled = !state.busy,
        )
    }
}

/** Nothing can be asked of a server without the client that asks it. */
@Composable
private fun MissingClient(state: PanelState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "sqlcmd is not installed",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Muted("sqlcmd is the arm64 client this extension uses to reach SQL Server.")
        CompactFilledButton(
            text = "Install sqlcmd",
            onClick = { state.installClient() },
            enabled = !state.busy,
            busy = state.busy,
        )
        if (state.log.isNotEmpty()) LogBlock(state.log.joinToString(Char(10).toString()))
    }
}

@Composable
private fun Body(state: PanelState) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { DatabasesHeader(state) }
        if (state.error.isNotEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                    StatusText(state.error, isError = true)
                }
            }
        }
        if (state.databases.isEmpty() && state.error.isEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    Muted("No databases on this server yet.")
                }
            }
        }
        items(state.databases, key = { it.name }) { db -> DatabaseRow(state, db) }
    }
}

@Composable
private fun DatabasesHeader(state: PanelState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel("Databases")
        Box(modifier = Modifier.weight(1f))
        IconAction(
            icon = jcIcon(JCodeIcon.Add),
            label = "New database",
            onClick = { state.createDatabase() },
            enabled = !state.busy,
        )
    }
}

/** One database: its name, its size, and the two things that can be done to it from here. */
@Composable
private fun DatabaseRow(state: PanelState, db: Database) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { state.open(db.name) }
            .handCursor()
            .padding(start = Space.lg, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = db.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (db.size.isNotEmpty()) Muted(db.size)
        }
        IconAction(
            icon = jcIcon(JCodeIcon.Rename),
            label = "Rename",
            onClick = { state.renameDatabase(db.name) },
            enabled = !state.busy,
        )
        IconAction(
            icon = jcIcon(JCodeIcon.Delete),
            label = "Drop",
            onClick = { state.dropDatabase(db.name) },
            enabled = !state.busy,
        )
    }
}
