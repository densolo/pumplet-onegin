package app.dc8.pumplet.onegin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import javax.swing.SwingUtilities

data class ProfileEntry(val name: String, val validation: Validation?, val loadError: String? = null)

/** A command offered in the UI: CLI arguments after the job file, and whether it writes to the target. */
enum class Command(val label: String, val args: List<String>, val writesTarget: Boolean, val help: String) {
    CHECK("Check connection", listOf("check"), false, "Connect to both databases and list what would be copied"),
    STATUS("Status", listOf("status"), false, "Show this job's sessions and runs from the state table"),
    DIFF("Diff", listOf("diff"), false, "Compare source and target schemas; read-only"),
    RUN("Run", listOf("run"), true, "diff, disable, copy and enable in one go"),
    FETCH_TEST("Fetch test", listOf("fetch-test"), false, "Read the source like a copy would and report the speed; never touches the target"),
    VALIDATE("Validate", listOf("validate"), false, "Check the job and secrets files, without connecting"),
    DISABLE("Disable", listOf("disable"), true, "Drop foreign keys / indexes and disable triggers on the target"),
    COPY("Copy", listOf("copy"), true, "Copy (or resume copying) the data"),
    ENABLE("Enable", listOf("enable"), true, "Restore what disable took away"),
    ENABLE_SKIP("Enable, skip pending", listOf("enable", "--skip-pending"), true, "Give up on objects that failed to restore"),
    RECOVER("Recover", listOf("recover"), true, "Restore everything a failed or killed run left dropped"),
    STATUS_ALL("Status (all jobs)", listOf("status", "--all"), false, "Sessions of every job in the state table"),
    ;

    companion object {
        val toolbar = listOf(CHECK, STATUS, DIFF, RUN, FETCH_TEST)
        val more = listOf(VALIDATE, DISABLE, COPY, ENABLE, ENABLE_SKIP, RECOVER, STATUS_ALL)
    }
}

enum class PaneTab(val label: String) { PROFILE("Profile"), OUTPUT("Output") }

/** UI state; every mutation happens on the UI thread. */
class AppState(val store: ProfileStore = ProfileStore()) {
    var profiles by mutableStateOf(listOf<ProfileEntry>())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    /** The form's current values; [saved] is what's on disk (null for a profile not saved yet). */
    var draft by mutableStateOf<Profile?>(null)
    var saved by mutableStateOf<Profile?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var running by mutableStateOf<Pair<String, Command>?>(null)
        private set
    var pendingConfirm by mutableStateOf<Command?>(null)
    var confirmDelete by mutableStateOf(false)
    var tab by mutableStateOf(PaneTab.PROFILE)
    var showAdvanced by mutableStateOf(false)

    private val outputs = mutableStateMapOf<String, SnapshotStateList<OutputLine>>()

    val dirty get() = draft != null && draft != saved

    private val runner = CommandRunner(
        onLine = { profile, line -> SwingUtilities.invokeLater { output(profile).add(line) } },
        onFinished = { profile, code ->
            SwingUtilities.invokeLater {
                running = null
                refresh(profile)
                if (code == 130) message = "stopped; run the same command again to continue"
            }
        },
    )

    fun output(profile: String): SnapshotStateList<OutputLine> = outputs.getOrPut(profile) { mutableStateListOf() }

    /** Lists profiles and validates each offline; never connects. */
    fun refresh(keep: String? = selected) {
        profiles = store.list().map { name ->
            runCatching { ProfileEntry(name, store.validate(name)) }.getOrElse { ProfileEntry(name, null, it.message ?: it.toString()) }
        }
        if (keep != null && profiles.any { it.name == keep } && saved?.name == keep) return
        if (keep != null && profiles.any { it.name == keep }) select(keep)
    }

    fun select(name: String) {
        selected = name
        val p = runCatching { store.load(name) }.getOrElse {
            message = "can't read profile $name: ${it.message}"
            null
        }
        saved = p
        draft = p
    }

    fun newProfile() {
        tab = PaneTab.PROFILE
        val p = Profile(name = store.freeName("new-job"))
        selected = null
        saved = null
        draft = p
    }

    fun duplicate() {
        val d = draft ?: return
        tab = PaneTab.PROFILE
        selected = null
        saved = null
        draft = d.copy(name = store.freeName(d.name + "-copy"))
    }

    /** Saves the form; returns true on success. Validation problems are shown but don't stop the save. */
    fun save(): Boolean {
        val d = draft ?: return false
        return try {
            store.save(d, previousName = saved?.name)
            saved = d
            selected = d.name
            refresh(d.name)
            true
        } catch (e: Exception) {
            message = e.message ?: e.toString()
            false
        }
    }

    fun revert() {
        draft = saved
    }

    fun delete() {
        val name = selected ?: return
        store.delete(name)
        outputs.remove(name)
        selected = null
        saved = null
        draft = null
        refresh(null)
    }

    /** Runs [command] for the selected profile, asking first if it writes to the target. */
    fun request(command: Command) {
        if (command.writesTarget) pendingConfirm = command else run(command)
    }

    fun run(command: Command) {
        pendingConfirm = null
        val name = selected ?: return
        if (dirty) {
            message = "save the profile first"
            return
        }
        val args = command.args + listOf("--config", store.jobFile(name).toString())
        if (runner.start(name, args)) {
            running = name to command
            tab = PaneTab.OUTPUT
        } else {
            message = "another command is still running"
        }
    }

    fun stop() = runner.stop()

    fun clearOutput() {
        selected?.let { output(it).clear() }
    }

    fun validationOf(name: String?) = profiles.firstOrNull { it.name == name }?.validation
}
