package dev.blamspot.jcode.ext.sql

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * The SQL Server client, native.
 *
 * The entry point JCode instantiates by name and splices into its own composition. Everything here
 * runs in JCode's process against JCode's Compose runtime — hence the compileOnly dependency rules
 * in the build script.
 *
 * One extension, two surfaces: the drawer that lists servers and databases, and the studio a
 * database opens into. The studio owns its own state and asks the server its own questions, because
 * it opens on its own — restored with a session, or reached from another tab — and cannot see what
 * the drawer decided.
 */
class SqlExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        val scope = rememberCoroutineScope()
        val view = params[JCodeNativeExtension.Params.VIEW].orEmpty()
        if (view.startsWith("db:")) {
            val studio = remember(host, scope, view) { StudioState(host, scope, view) }
            LaunchedEffect(studio) { studio.boot() }
            StudioPage(studio)
        } else {
            val panel = remember(host, scope) { PanelState(host, scope) }
            LaunchedEffect(panel) { panel.boot() }
            SqlPanel(panel)
        }
    }
}
