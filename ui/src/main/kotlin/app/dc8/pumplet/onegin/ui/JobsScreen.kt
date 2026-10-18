package app.dc8.pumplet.onegin.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dc8.pumplet.onegin.schema.Comparison
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.DiffTable
import app.dc8.pumplet.onegin.schema.TableAction
import app.dc8.pumplet.onegin.schema.Verdict
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val JobRed = Color(0xFFC62828)
private val JobAmber = Color(0xFFB26A00)
private val JobGreen = Color(0xFF2E7D32)
private val JobMuted = Color(0xFF6B6B6B)
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Jobs: the commands run in this window on the left, the selected one's page on the right. */
@Composable
fun JobsScreen(state: AppState) {
    Row(Modifier.fillMaxSize()) {
        JobList(state, Modifier.width(280.dp).fillMaxHeight())
        Divider(Modifier.fillMaxHeight().width(1.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            val job = state.selectedJob
            if (job == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No job selected", fontWeight = FontWeight.Bold)
                    Text("Every command you run from a profile (Check connection, Status, Diff, Run, …) becomes a job here.", color = JobMuted)
                }
            } else {
                JobPage(state, job)
            }
        }
    }
}

@Composable
private fun JobList(state: AppState, modifier: Modifier) {
    // Running jobs show a live duration.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(state.runningJob) {
        while (state.runningJob != null) {
            delay(1000)
            tick++
        }
    }
    Column(modifier.background(Color(0xFFF4F4F4)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jobs", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.clearFinishedJobs() }, enabled = state.jobs.any { it.status != JobStatus.RUNNING }) {
                Text("Clear finished")
            }
        }
        if (state.jobs.isEmpty()) Text("none yet", color = JobMuted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.jobs, key = { it.id }) { job ->
                val selected = job == state.selectedJob
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Color(0xFFDDE7F5) else Color.Transparent)
                        .clickable { state.selectedJob = job }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        StatusChip(job.status)
                    }
                    @Suppress("UNUSED_EXPRESSION") tick
                    Text("${job.profile} · ${TIME.format(job.startedAt)} · ${duration(job.duration)}", color = JobMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: JobStatus) {
    val color = statusColor(status)
    Text(
        status.label,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun statusColor(status: JobStatus) = when (status) {
    JobStatus.RUNNING -> JobAmber
    JobStatus.SUCCEEDED -> JobGreen
    JobStatus.BLOCKED, JobStatus.FAILED -> JobRed
    JobStatus.STOPPED -> JobMuted
}

/** A job: what ran, how it went, and its output (or, for a diff, its report). */
@Composable
private fun JobPage(state: AppState, job: Job) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("for ${job.profile}", color = JobMuted)
            StatusChip(job.status)
            Spacer(Modifier.weight(1f))
            if (job.status == JobStatus.RUNNING) {
                Button(onClick = { state.stop() }) { Text("Stop") }
            } else {
                OutlinedButton(onClick = { state.rerun(job) }, enabled = state.runningJob == null) { Text("Run again") }
            }
        }
        val details = buildString {
            append("started ${TIME.format(job.startedAt)} · ${duration(job.duration)}")
            job.exitCode?.let { append(" · exit code $it") }
        }
        Text(details, color = JobMuted, fontSize = 12.sp)
        Text("pumplet-onegin ${job.args.joinToString(" ")}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = JobMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (job.diff != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ViewButton("Diff report", job.view == JobView.DIFF_REPORT) { job.view = JobView.DIFF_REPORT }
                ViewButton("Output", job.view == JobView.OUTPUT) { job.view = JobView.OUTPUT }
            }
            Spacer(Modifier.height(6.dp))
        }
        val diff = job.diff
        if (diff != null && job.view == JobView.DIFF_REPORT) DiffReportView(diff, Modifier.weight(1f).fillMaxWidth())
        else OutputView(job.lines, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun ViewButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

/**
 * The diff as a table: every compared object with its source and target definition and
 * whether it's the same, a compatible difference or a blocker.
 */
@Composable
fun DiffReportView(report: DiffReport, modifier: Modifier) {
    var showAll by remember(report) { mutableStateOf(false) }
    val rows = if (showAll) report.comparisons else report.differences
    Column(modifier) {
        val overallColor = when {
            report.blocking -> JobRed
            report.differences.isEmpty() -> JobGreen
            else -> JobAmber
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(report.overall, color = overallColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            val actions = TableAction.entries.mapNotNull { a -> report.tables.count { it.action == a }.takeIf { it > 0 }?.let { "$it ${a.name.lowercase()}" } }
            Text("tables: ${actions.joinToString(", ").ifEmpty { "none" }}", color = JobMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showAll = !showAll }) {
                Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                Text("Show identical objects too", fontSize = 13.sp)
            }
        }
        Text("source: ${report.source}    target: ${report.target}", color = JobMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().background(Color(0xFFE8E8E8)).padding(vertical = 4.dp)) {
            HeaderCell("Object", 0.24f); HeaderCell("Kind", 0.10f); HeaderCell("Source", 0.27f); HeaderCell("Target", 0.27f); HeaderCell("Status", 0.12f)
        }
        if (rows.isEmpty()) {
            Text(if (showAll) "nothing was compared" else "no differences", color = JobGreen, modifier = Modifier.padding(8.dp))
        }
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    items(rows) { c -> ComparisonRow(c) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(weight).padding(horizontal = 6.dp))

@Composable
private fun ComparisonRow(c: Comparison) {
    val tint = when (c.verdict) {
        Verdict.BLOCKER -> Color(0xFFFDECEC)
        Verdict.COMPATIBLE -> Color(0xFFFFF6E5)
        Verdict.SAME -> Color.Transparent
    }
    Column(Modifier.fillMaxWidth().background(tint).padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Cell(c.objectName, 0.24f)
            Cell(c.kind, 0.10f, color = JobMuted)
            Cell(c.source ?: "—", 0.27f, mono = true)
            Cell(c.target ?: "—", 0.27f, mono = true)
            val (color, weight) = when (c.verdict) {
                Verdict.BLOCKER -> JobRed to FontWeight.Bold
                Verdict.COMPATIBLE -> JobAmber to FontWeight.Normal
                Verdict.SAME -> JobGreen to FontWeight.Normal
            }
            Text(DiffTable.status(c.verdict), color = color, fontWeight = weight, fontSize = 12.sp, modifier = Modifier.weight(0.12f).padding(horizontal = 6.dp))
        }
        c.note?.let { Text(it, color = JobMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp)) }
        Divider(color = Color(0xFFEEEEEE))
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, mono: Boolean = false, color: Color = Color.Unspecified) =
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.weight(weight).padding(horizontal = 6.dp),
    )

private fun duration(d: Duration): String {
    val s = d.seconds
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m%02ds".format(s % 60)
        else -> "${s / 3600}h%02dm".format(s % 3600 / 60)
    }
}

