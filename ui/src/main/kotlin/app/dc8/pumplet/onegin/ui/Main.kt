package app.dc8.pumplet.onegin.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** The desktop UI. Run from the IDE, or: java -jar ui/target/pumplet-onegin-ui.jar */
fun main() {
    val state = AppState()
    application {
        Window(
            onCloseRequest = { state.stop(); exitApplication() },
            title = "pumplet-onegin",
            state = rememberWindowState(width = 1320.dp, height = 860.dp),
        ) {
            MaterialTheme { App(state) }
        }
    }
}
