package app.dc8.pumplet.onegin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** The sections of the left rail. */
enum class Screen(val label: String, val icon: String) {
    PROFILES("Profiles", "☰"),
    JOBS("Jobs", "▶"),
}

/** UI state; every mutation happens on the UI thread. */
class AppState(val store: ProfileStore = ProfileStore()) {
    var screen by mutableStateOf(Screen.PROFILES)
    var profiles by mutableStateOf(listOf<ProfileEntry>())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    /** The form's current values; [saved] is what's on disk (null for a profile not saved yet). */
    var draft by mutableStateOf<Profile?>(null)
    var saved by mutableStateOf<Profile?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var pendingConfirm by mutableStateOf<Command?>(null)
    var confirmDelete by mutableStateOf(false)
    var showAdvanced by mutableStateOf(false)

    /** Every command run in this window, newest first. */
    val jobs = mutableStateListOf<Job>()
    var selectedJob by mutableStateOf<Job?>(null)
    private var nextJobId = 1

    val dirty get() = draft != null && draft != saved
    val runningJob: Job? get() = jobs.firstOrNull { it.status == JobStatus.RUNNING }

    private val runner = CommandRunner(
        onLine = { key, line -> SwingUtilities.invokeLater { job(key)?.lines?.add(line) } },
        onFinished = { key, code ->
            val job = jobs.firstOrNull { it.key == key }
            SwingUtilities.invokeLater {
                job?.finish(code)
                job?.let { refresh(selected) }
            }
        },
    )

    private fun job(key: String) = jobs.firstOrNull { it.key == key }

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
        val p = Profile(name = store.freeName("new-job"))
        selected = null
        saved = null
        draft = p
    }

    fun duplicate() {
        val d = draft ?: return
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
        start(name, command)
    }

    /** Runs [command] again for [job]'s profile (asking first if it writes to the target). */
    fun rerun(job: Job) {
        if (!store.exists(job.profile)) {
            message = "profile ${job.profile} no longer exists"
            return
        }
        if (job.command.writesTarget) {
            select(job.profile)
            pendingConfirm = job.command
        } else {
            start(job.profile, job.command)
        }
    }

    /** Starts a job and shows it on the Jobs screen. */
    private fun start(profile: String, command: Command) {
        val jobFile = store.jobFile(profile)
        val job = Job(nextJobId, profile, command, command.args + listOf("--config", jobFile.toString()), jobFile)
        if (runningJob != null) {
            message = "another command is still running"
            return
        }
        nextJobId++
        jobs.add(0, job)
        if (!runner.start(job.key, job.args)) {
            jobs.remove(job)
            message = "another command is still running"
            return
        }
        selectedJob = job
        screen = Screen.JOBS
    }

    fun stop() = runner.stop()

    fun clearFinishedJobs() {
        jobs.removeAll { it.status != JobStatus.RUNNING }
        if (selectedJob !in jobs) selectedJob = jobs.firstOrNull()
    }

    fun validationOf(name: String?) = profiles.firstOrNull { it.name == name }?.validation
}
