package app.dc8.pumplet.onegin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.NavigationRail
import androidx.compose.material.NavigationRailItem
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFB26A00)
private val Green = Color(0xFF2E7D32)
private val Muted = Color(0xFF6B6B6B)

@Composable
fun App(state: AppState) {
    LaunchedEffect(Unit) { state.refresh() }
    Row(Modifier.fillMaxSize()) {
        Rail(state)
        Divider(Modifier.fillMaxHeight().width(1.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (state.screen) {
                Screen.PROFILES -> Row(Modifier.fillMaxSize()) {
                    ProfileList(state, Modifier.width(250.dp).fillMaxHeight())
                    Divider(Modifier.fillMaxHeight().width(1.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val draft = state.draft
                        if (draft == null) EmptyHint(state) else ProfilePane(state, draft)
                    }
                }
                Screen.JOBS -> JobsScreen(state)
            }
        }
    }
    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { state.message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { state.message = null }) { Text("OK") } },
        )
    }
    state.pendingConfirm?.let { cmd ->
        val target = state.draft?.targetUrl.orEmpty()
        AlertDialog(
            onDismissRequest = { state.pendingConfirm = null },
            title = { Text("${cmd.label}?") },
            text = { Text("This writes to the target database:\n$target\n\n${cmd.help}.") },
            confirmButton = { Button(onClick = { state.run(cmd) }) { Text(cmd.label) } },
            dismissButton = { TextButton(onClick = { state.pendingConfirm = null }) { Text("Cancel") } },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { state.confirmDelete = false },
            title = { Text("Delete profile ${state.selected}?") },
            text = { Text("Removes its folder: job, passwords, log and diff report. The databases are not touched.") },
            confirmButton = { Button(onClick = { state.confirmDelete = false; state.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { state.confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileList(state: AppState, modifier: Modifier) {
    Column(modifier.background(Color(0xFFF4F4F4)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Profiles", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.refresh() }) { Text("Reload") }
            TextButton(onClick = { state.newProfile() }) { Text("+ New") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.profiles, key = { it.name }) { p ->
                val selected = p.name == state.selected
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Color(0xFFDDE7F5) else Color.Transparent)
                        .clickable { state.select(p.name) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(p.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    val (text, color) = when (val v = p.validation) {
                        is Validation.Ok -> (if (v.warnings.isEmpty()) "valid" else "valid, ${v.warnings.size} warning(s)") to Green
                        is Validation.Invalid -> "${v.problems.size} problem(s)" to Red
                        null -> "unreadable: ${p.loadError}" to Red
                    }
                    Text(text, color = color, fontSize = 12.sp)
                }
            }
        }
        Text(state.store.root.toString(), color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyHint(state: AppState) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No profile selected", fontWeight = FontWeight.Bold)
        Text("Pick a profile on the left, or create one with + New.")
        Text("Profiles are folders under ${state.store.root} with job.yaml and secrets.yaml.", color = Muted)
        Text("Nothing connects to a database until you press a button.", color = Muted)
    }
}

@Composable
private fun ProfilePane(state: AppState, draft: Profile) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Toolbar(state)
        Spacer(Modifier.height(10.dp))
        ProfileForm(state, draft, Modifier.widthIn(max = 720.dp).weight(1f))
    }
}

/** The narrow bar on the far left: one icon per section; Jobs shows how many are running. */
@Composable
private fun Rail(state: AppState) {
    NavigationRail(backgroundColor = Color(0xFFEDEDED), elevation = 0.dp) {
        Spacer(Modifier.height(8.dp))
        for (s in Screen.entries) {
            NavigationRailItem(
                selected = state.screen == s,
                onClick = { state.screen = s },
                icon = {
                    val running = if (s == Screen.JOBS) state.jobs.count { it.status == JobStatus.RUNNING } else 0
                    BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
                        Text(s.icon, fontSize = 20.sp)
                    }
                },
                label = { Text(s.label, fontSize = 12.sp) },
                selectedContentColor = MaterialTheme.colors.primary,
                unselectedContentColor = Muted,
            )
        }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    val running = state.runningJob
    val canRun = running == null && state.selected != null && !state.dirty
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (cmd in Command.toolbar) {
            if (cmd == Command.RUN) Button(onClick = { state.request(cmd) }, enabled = canRun) { Text(cmd.label) }
            else OutlinedButton(onClick = { state.request(cmd) }, enabled = canRun) { Text(cmd.label) }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = canRun) { Text("More ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (cmd in Command.more) {
                    DropdownMenuItem(onClick = { open = false; state.request(cmd) }) {
                        Column {
                            Text(cmd.label)
                            Text(cmd.help, fontSize = 11.sp, color = Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (running != null) {
            TextButton(onClick = { state.selectedJob = running; state.screen = Screen.JOBS }) {
                Text("${running.title} running for ${running.profile}…", color = Amber)
            }
            Button(onClick = { state.stop() }) { Text("Stop") }
        } else if (state.dirty) {
            Text("unsaved changes: save to run commands", color = Amber)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ProfileForm(state: AppState, p: Profile, modifier: Modifier) {
    fun update(f: (Profile) -> Profile) {
        state.draft = f(p)
    }
    // Compact rows: no 48dp minimum touch target around checkboxes.
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        val formScroll = rememberScrollState()
        Box(modifier) {
            Column(Modifier.verticalScroll(formScroll).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { state.save() }, enabled = state.dirty && state.runningJob == null) { Text("Save") }
                    OutlinedButton(onClick = { state.revert() }, enabled = state.dirty && state.saved != null) { Text("Revert") }
                    OutlinedButton(onClick = { state.duplicate() }, enabled = !state.dirty) { Text("Duplicate") }
                    OutlinedButton(onClick = { state.confirmDelete = true }, enabled = state.selected != null && state.runningJob == null) { Text("Delete") }
                }
                ValidationView(state.validationOf(state.selected), saved = state.saved != null)

                TextRow("Profile name", p.name) { v -> update { it.copy(name = v.trim()) } }

                Section("Source (read-only)")
                TextRow("JDBC URL", p.sourceUrl) { v -> update { it.copy(sourceUrl = v) } }
                TextRow("User", p.sourceUser) { v -> update { it.copy(sourceUser = v) } }
                TextRow("Password", p.sourcePassword, password = true) { v -> update { it.copy(sourcePassword = v) } }
                TextRow("Schemas", p.schemas.joinToString(", "), placeholder = "comma-separated") { v -> update { it.copy(schemas = splitList(v)) } }

                Section("Target")
                TextRow("JDBC URL", p.targetUrl) { v -> update { it.copy(targetUrl = v) } }
                TextRow("User", p.targetUser) { v -> update { it.copy(targetUser = v) } }
                TextRow("Password", p.targetPassword, password = true) { v -> update { it.copy(targetPassword = v) } }

                Section("Tables")
                TextRow("Include", p.include.joinToString(", "), placeholder = "schema.table globs, comma-separated; empty = all") { v ->
                    update { it.copy(include = splitList(v)) }
                }
                TextRow("Exclude", p.exclude.joinToString(", "), placeholder = "e.g. app.audit_*") { v -> update { it.copy(exclude = splitList(v)) } }

                Spacer(Modifier.height(6.dp))
                val customised = p.customisedAdvanced()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { state.showAdvanced = !state.showAdvanced }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                        Text(if (state.showAdvanced) "▾ Hide advanced settings" else "▸ Advanced settings", fontSize = 13.sp)
                    }
                    if (!state.showAdvanced && customised.isNotEmpty()) {
                        Text("  changed: ${customised.joinToString(", ")}", color = Amber, fontSize = 12.sp)
                    }
                }
                if (state.showAdvanced) {
                    Section("Tuning")
                    NumberRow("Readers", p.readers.toLong(), "source connections; more hide a remote source's latency") { v -> update { it.copy(readers = v.toInt()) } }
                    NumberRow("Writers", p.writers.toLong(), "target connections") { v -> update { it.copy(writers = v.toInt()) } }
                    NumberRow("Rows per chunk", p.rowsPerChunk, "one commit per chunk") { v -> update { it.copy(rowsPerChunk = v) } }
                    NumberRow("Min rows to split", p.minRowsToSplit, "smaller tables are one chunk") { v -> update { it.copy(minRowsToSplit = v) } }
                    NumberRow("Buffer", p.maxMemoryMb.toLong(), "MB, between readers and writers") { v -> update { it.copy(maxMemoryMb = v.toInt()) } }

                    Section("During the load (put back by enable)")
                    CheckRow("Drop foreign keys", p.dropForeignKeys, "needed: tables and chunks load in parallel, children may come before parents") { v ->
                        update { it.copy(dropForeignKeys = v) }
                    }
                    CheckRow("Drop secondary indexes", p.dropSecondaryIndexes, "plain (non-constraint) indexes, rebuilt once after the load; pays off for big tables") { v ->
                        update { it.copy(dropSecondaryIndexes = v) }
                    }
                    CheckRow("Disable user triggers", p.disableUserTriggers, "so triggers don't fire for copied rows") { v -> update { it.copy(disableUserTriggers = v) } }

                    Section("Other")
                    CheckRow("Raise sequences", p.raiseSequences, "to the source's values if lower, so local inserts don't reuse source ids") { v ->
                        update { it.copy(raiseSequences = v) }
                    }
                    CheckRow("Allow type differences", p.allowTypeDifferences, "copy differing columns as text instead of blocking") { v ->
                        update { it.copy(allowTypeDifferences = v) }
                    }
                    TextRow("State table", p.stateTable, placeholder = "default: <first schema>.pumplet_onegin_state") { v -> update { it.copy(stateTable = v.trim()) } }
                }
                state.selected?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Files: ${state.store.dir(it)}", color = Muted, fontSize = 11.sp)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(formScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ValidationView(v: Validation?, saved: Boolean) {
    when {
        !saved -> Text("new profile: save to validate", color = Muted, fontSize = 12.sp)
        v is Validation.Ok -> {
            Text("✓ valid (checked offline, nothing connected)", color = Green, fontSize = 12.sp)
            v.warnings.forEach { Text("warning: $it", color = Amber, fontSize = 12.sp) }
        }
        v is Validation.Invalid -> v.problems.forEach { Text("• $it", color = Red, fontSize = 12.sp) }
    }
}

private val LabelWidth = 170.dp
private val FieldHeight = 30.dp
private val FieldText = TextStyle(fontSize = 13.sp)

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Divider(Modifier.padding(bottom = 2.dp))
}

/** Label on the left, the input next to it. */
@Composable
private fun FormRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(LabelWidth))
        content()
    }
}

@Composable
private fun TextRow(label: String, value: String, password: Boolean = false, placeholder: String? = null, onChange: (String) -> Unit) =
    FormRow(label) { CompactField(value, onChange, Modifier.weight(1f), password = password, placeholder = placeholder) }

/** Keeps what was typed while it isn't a number yet; only valid numbers reach the profile. */
@Composable
private fun NumberRow(label: String, value: Long, hint: String? = null, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    FormRow(label) {
        CompactField(
            value = text,
            onChange = { v -> text = v.filter { it.isDigit() }; text.toLongOrNull()?.takeIf { it > 0 }?.let(onChange) },
            modifier = Modifier.width(120.dp),
            isError = text.toLongOrNull()?.let { it <= 0 } ?: true,
        )
        hint?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun CheckRow(label: String, value: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) =
    FormRow(label) {
        Checkbox(checked = value, onCheckedChange = onChange, modifier = Modifier.height(FieldHeight))
        hint?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).clickable { onChange(!value) }) }
    }

/** A small single-line field: thin border (primary when focused, red on error), little padding. */
@Composable
private fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    password: Boolean = false,
    isError: Boolean = false,
    placeholder: String? = null,
) {
    var show by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        isError -> Red
        focused -> MaterialTheme.colors.primary
        else -> Color(0xFFBDBDBD)
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = FieldText,
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = interaction,
        modifier = modifier.height(FieldHeight),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().border(1.dp, border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = FieldText, color = Muted)
                    inner()
                }
                if (password) {
                    Text(
                        if (show) "hide" else "show",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(start = 6.dp).clickable { show = !show },
                    )
                }
            }
        },
    )
}

/** Command output: monospace, coloured by stream, scrolling both ways, following new lines. */
@Composable
fun OutputView(lines: List<OutputLine>, modifier: Modifier) {
    Column(modifier) {
        Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            val hScroll = rememberScrollState()
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
            // Lines stay whole (the CLI aligns columns): the content is as wide as the longest line,
            // measured in the monospace font, and the pane scrolls sideways instead of wrapping.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val charWidth = remember(measurer) { with(density) { measurer.measure("M", OutputText).size.width.toDp() } }
            val longest = lines.maxOfOrNull { it.text.length } ?: 0
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val contentWidth = maxOf(maxWidth - Gutter * 2, charWidth * longest + 8.dp)
                SelectionContainer(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = Gutter, bottom = Gutter).horizontalScroll(hScroll)) {
                    LazyColumn(state = listState, modifier = Modifier.width(contentWidth)) {
                        items(lines) { line ->
                            val color = when {
                                line.stream == OutputLine.Stream.INFO -> Color(0xFF7FB2F0)
                                line.text.startsWith("warning:") -> Color(0xFFE5B567)
                                line.stream == OutputLine.Stream.ERR || "PROBLEM" in line.text || "FAILED" in line.text || "PENDING" in line.text -> Color(0xFFF08080)
                                else -> Color(0xFFDDDDDD)
                            }
                            Text(line.text, color = color, style = OutputText, softWrap = false, maxLines = 1)
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    style = DarkScrollbar,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = Gutter),
                )
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(hScroll),
                    style = DarkScrollbar,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = Gutter),
                )
            }
        }
    }
}

private val OutputText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** Room kept free for the scrollbars along the right and bottom edges of the output. */
private val Gutter = 12.dp

/** Scrollbars that show on the dark output background. */
private val DarkScrollbar = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 300,
    unhoverColor = Color.White.copy(alpha = 0.25f),
    hoverColor = Color.White.copy(alpha = 0.5f),
)

private fun splitList(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

