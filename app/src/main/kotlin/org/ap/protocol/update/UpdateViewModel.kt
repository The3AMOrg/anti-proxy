package org.ap.protocol.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ap.protocol.BuildConfig
import java.io.File

data class UpdateSettings(
    val checkOnStart: Boolean = true,
    val includePrerelease: Boolean = false,
    val notify: Boolean = true,
    val directDownload: Boolean = true,
    val warnBeforeDownload: Boolean = true
)

/** Update preferences, shared by the screens and the daily background check. */
class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun read() = UpdateSettings(
        checkOnStart = prefs.getBoolean(CHECK_ON_START, true),
        includePrerelease = prefs.getBoolean(PRERELEASE, false),
        notify = prefs.getBoolean(NOTIFY, true),
        directDownload = prefs.getBoolean(DIRECT, true),
        warnBeforeDownload = prefs.getBoolean(WARN, true)
    )

    fun write(s: UpdateSettings) = prefs.edit {
        putBoolean(CHECK_ON_START, s.checkOnStart)
        putBoolean(PRERELEASE, s.includePrerelease)
        putBoolean(NOTIFY, s.notify)
        putBoolean(DIRECT, s.directDownload)
        putBoolean(WARN, s.warnBeforeDownload)
    }

    var seenVersionCode: Int
        get() = prefs.getInt(SEEN_VERSION, 0)
        set(v) = prefs.edit { putInt(SEEN_VERSION, v) }

    /** The background check notifies once per release, not every day. */
    var notifiedTag: String?
        get() = prefs.getString(NOTIFIED_TAG, null)
        set(v) = prefs.edit { putString(NOTIFIED_TAG, v) }

    private companion object {
        const val CHECK_ON_START = "check_on_start"
        const val PRERELEASE = "include_prerelease"
        const val NOTIFY = "notify"
        const val DIRECT = "direct_download"
        const val WARN = "warn_before_download"
        const val SEEN_VERSION = "seen_version_code"
        const val NOTIFIED_TAG = "notified_tag"
    }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Started : DownloadState
    data class Progress(val fraction: Float) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

data class ChangelogState(val loading: Boolean = false, val releases: List<Release> = emptyList(), val failed: Boolean = false)

/**
 * Checks GitHub releases on start (if enabled) and on demand, downloads the right APK for this
 * phone, and shows the changelog once after an upgrade.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UpdatePrefs(app)

    private val _settings = MutableStateFlow(prefs.read())
    val settings: StateFlow<UpdateSettings> = _settings.asStateFlow()

    /** A newer release, once found. Keeps the home screen's update button visible. */
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _showUpdateSheet = MutableStateFlow(false)
    val showUpdateSheet: StateFlow<Boolean> = _showUpdateSheet.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _changelog = MutableStateFlow(ChangelogState())
    val changelog: StateFlow<ChangelogState> = _changelog.asStateFlow()

    private val _showChangelog = MutableStateFlow(false)
    val showChangelog: StateFlow<Boolean> = _showChangelog.asStateFlow()

    private var downloadJob: Job? = null
    private var downloadId = -1L

    init {
        if (_settings.value.checkOnStart) check(manual = false)
        // First open of a new version: show what changed, once. Skipped when there are no notes to show.
        if (prefs.seenVersionCode < BuildConfig.VERSION_CODE) {
            viewModelScope.launch {
                loadChangelog()
                if (_changelog.value.releases.isNotEmpty()) _showChangelog.value = true
                else prefs.seenVersionCode = BuildConfig.VERSION_CODE
            }
        }
        UpdateCheckWorker.schedule(app, _settings.value.notify)
    }

    fun update(change: (UpdateSettings) -> UpdateSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.write(next)
        UpdateCheckWorker.schedule(getApplication(), next.notify)
    }

    fun check(manual: Boolean) {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            val result = GitHubReleases.check(BuildConfig.VERSION_NAME, _settings.value.includePrerelease)
            _checking.value = false
            when (result) {
                is UpdateResult.Success ->
                    if (result.isNewer) {
                        _available.value = result.release
                        _showUpdateSheet.value = true
                    } else if (manual) {
                        _messages.emit("You're on the latest version (${versionLabel()}).")
                    }
                UpdateResult.NoReleases -> if (manual) _messages.emit("No releases have been published yet.")
                UpdateResult.Timeout -> if (manual) _messages.emit("GitHub took too long to answer. Try again.")
                UpdateResult.NetworkError -> if (manual) _messages.emit("Couldn't check for updates. Make sure you're online.")
                UpdateResult.UnknownError -> if (manual) _messages.emit("Couldn't check for updates. Try again later.")
            }
        }
    }

    fun openUpdateSheet() { if (_available.value != null) _showUpdateSheet.value = true }

    fun dismissUpdateSheet() { _showUpdateSheet.value = false }

    fun openChangelog() {
        _showChangelog.value = true
        if (_changelog.value.releases.isEmpty()) viewModelScope.launch { loadChangelog() }
    }

    fun dismissChangelog() {
        _showChangelog.value = false
        prefs.seenVersionCode = BuildConfig.VERSION_CODE
    }

    /** Releases up to the installed version: what this phone actually got, newest first. */
    private suspend fun loadChangelog() {
        _changelog.value = _changelog.value.copy(loading = true, failed = false)
        _changelog.value = try {
            val releases = GitHubReleases.all().filter { compareVersions(it.tagName, BuildConfig.VERSION_NAME) <= 0 }
            ChangelogState(releases = releases)
        } catch (_: Exception) {
            ChangelogState(failed = true)
        }
    }

    /** Downloads [apk] with the system download manager, which shows its own progress notification. */
    fun download(apk: ApkAsset) {
        if (downloadJob?.isActive == true) return
        val context = getApplication<Application>()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _download.value = DownloadState.Started
                val dir = context.getExternalFilesDir(null) ?: error("No storage for the download")
                dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
                val file = File(dir, "AntiProxy-update.apk")
                val dm = context.getSystemService(DownloadManager::class.java)
                downloadId = dm.enqueue(
                    DownloadManager.Request(Uri.parse(apk.downloadUrl))
                        .setTitle("AntiProxy update")
                        .setDescription(apk.name)
                        .setDestinationUri(Uri.fromFile(file))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                )
                val query = DownloadManager.Query().setFilterById(downloadId)
                while (isActive) {
                    val finished = dm.query(query)?.use { c ->
                        if (!c.moveToFirst()) return@use DownloadState.Failed("The download was removed.")
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Done(file)
                            DownloadManager.STATUS_FAILED -> DownloadState.Failed("The download failed. Check your connection and try again.")
                            else -> {
                                if (total > 0) _download.value = DownloadState.Progress(done.toFloat() / total)
                                null
                            }
                        }
                    }
                    if (finished != null) {
                        _download.value = finished
                        break
                    }
                    delay(200)
                }
            } catch (e: Exception) {
                _download.value = DownloadState.Failed(e.message ?: "The download failed.")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        if (downloadId != -1L) getApplication<Application>().getSystemService(DownloadManager::class.java).remove(downloadId)
        downloadId = -1L
        _download.value = DownloadState.Idle
    }

    fun resetDownload() { _download.value = DownloadState.Idle }
}

fun versionLabel() = "v" + BuildConfig.VERSION_NAME
