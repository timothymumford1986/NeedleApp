package app.needler.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.network.DiagnosticsEntry
import app.needler.core.network.NetworkDiagnostics
import app.needler.core.network.SessionDiagnosticsLog
import app.needler.settings.SettingsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Diagnostics screen: reads the session log, writes it to a file when the user asks, and
 * empties it when the user asks that instead.
 *
 * REQUIREMENTS.md "Observability" is the whole specification, and two of its clauses shape this class.
 *
 * ## "covering the last session" — why the log is not injected
 *
 * It is [NetworkDiagnostics.sessionLog], read directly, not a constructor dependency. That is
 * deliberate and it is documented on the property itself: the buffer has to be collecting from
 * `NeedlerApplication.onCreate`, which runs before anything has asked Hilt for anything, because the
 * requests a bug report most often needs are the first ones of a cold start — the capability probe and
 * the delta sync. A graph-scoped buffer would be constructed the first time this screen opened, by
 * which point the session it was meant to cover has happened.
 *
 * The cost is that this `ViewModel` cannot be unit-tested with a fake log. That cost is paid where it
 * is cheapest: everything worth asserting about this screen is either in `:core:network`, where
 * `SessionDiagnosticsLogTest` tests the buffer and the redaction directly, or in [diagnosticsLine] and
 * [DiagnosticsExport.header], which are pure functions this class only calls.
 *
 * ## "it must never leave the device automatically" — what this class does not do
 *
 * There is no upload, no endpoint, no crash reporter and no background work. The only thing that ever
 * writes the log anywhere is [onShare], which is a user tapping a button, and what it writes is one
 * file in the app's own cache that another app can read only if the user then picks one out of a
 * chooser. REQUIREMENTS.md "Security" rule 4 forbids "third-party analytics or crash reporting that
 * transmits server URLs or library contents", and a request log is nothing but server URLs.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val log: SessionDiagnosticsLog = NetworkDiagnostics.sessionLog

    private val mutableState: MutableStateFlow<DiagnosticsUiState> =
        MutableStateFlow(readSnapshot())

    val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    /**
     * Re-reads the buffer.
     *
     * The screen calls this when it appears and when the user asks. It is not a `Flow` for the reason
     * [DiagnosticsUiState] records: the buffer is written from OkHttp's threads, and a list that
     * re-measured itself on every request in flight would be unreadable under a scrolling thumb.
     */
    fun onRefresh() {
        mutableState.update { current ->
            readSnapshot().copy(
                exporting = current.exporting,
                pendingShare = current.pendingShare,
                // A notice survives a refresh: "wrote the file" is about the tap that just happened,
                // not about the snapshot.
                notice = current.notice,
            )
        }
    }

    /**
     * Writes the log to `<cacheDir>/diagnostics/needler-diagnostics.txt` and asks the screen to open a
     * chooser for it.
     *
     * Guarded on [DiagnosticsUiState.exporting] rather than on the button being disabled, because two
     * taps arriving before the first write finishes must not produce two writes to the same path.
     *
     * The failure path exists and is not theoretical: `cacheDir` can be reclaimed under storage
     * pressure between the `mkdirs` and the write, and REQUIREMENTS.md's whole storage chapter is about
     * devices that are full. A share that silently did nothing would be the worst possible behaviour
     * for the one button on this screen.
     */
    fun onShare() {
        if (mutableState.value.exporting) return
        mutableState.update { it.copy(exporting = true, notice = null) }
        viewModelScope.launch {
            // Three failures, none of them recoverable here and none of them worth a crash on the
            // screen a person opened because something was already wrong: the write itself
            // (IOException - a cache reclaimed under storage pressure), a cache directory this process
            // cannot write to (SecurityException), and FileProvider refusing a path outside its
            // configured roots (IllegalArgumentException). CancellationException is deliberately not
            // caught: a cancelled coroutine must stay cancelled.
            val share: DiagnosticsShare? = try {
                withContext(Dispatchers.IO) { writeExport() }
            } catch (failure: IOException) {
                null
            } catch (failure: SecurityException) {
                null
            } catch (failure: IllegalArgumentException) {
                null
            }
            mutableState.update { current ->
                if (share == null) {
                    current.copy(exporting = false, notice = EXPORT_FAILED)
                } else {
                    current.copy(
                        exporting = false,
                        pendingShare = share,
                        notice = "Wrote " + share.fileName + ", " +
                            SettingsFormat.bytes(share.byteCount) +
                            ". It has not been sent anywhere; choose where it goes.",
                    )
                }
            }
        }
    }

    /**
     * Called once the screen has opened the chooser, so a rotation does not open a second one.
     *
     * The pattern `SettingsUiState.signedOut` uses, for the same reason: the `ViewModel` records that
     * something should happen and the thing that owns a window does it.
     */
    fun onShareHandled() {
        mutableState.update { it.copy(pendingShare = null) }
    }

    /**
     * Empties the buffer and deletes any export written from it.
     *
     * Offered because a log is most useful when it covers one reproduction of one failure: clear it, do
     * the thing that breaks, share that. Deliberately **not** a two-tap destructive action — the
     * pattern `SettingsUiState.DestructiveSettingsAction` exists for, and reserved there for the two
     * actions that lose something. Nothing here is lost: these lines are a record of the last few
     * minutes, they are re-created by using the app, and nothing on the server or on this device
     * depends on them. Putting a confirmation in front of it would make the log tiring to use for its
     * main purpose, which is the same argument REQUIREMENTS.md makes for per-album removal.
     */
    fun onClear() {
        log.clear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DiagnosticsExport.deleteExport(context) }
            mutableState.value = readSnapshot().copy(notice = CLEARED)
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun readSnapshot(): DiagnosticsUiState {
        val entries: List<DiagnosticsEntry> = log.snapshot()
        return DiagnosticsUiState(
            lines = entries.mapIndexed { index, entry ->
                diagnosticsLine(
                    index = index,
                    clock = entry.clock,
                    level = entry.level,
                    source = entry.source,
                    message = entry.message,
                )
            },
            droppedCount = log.droppedCount,
            capacity = log.capacity,
        )
    }

    /** Blocking. Runs on [Dispatchers.IO]; see [onShare]. */
    private fun writeExport(): DiagnosticsShare {
        val header: List<String> = DiagnosticsExport.headerForDevice(
            context = context,
            exportedAt = Instant.now().toString(),
        )
        val file: File = DiagnosticsExport.write(context, log.render(header))
        return DiagnosticsShare(
            uri = DiagnosticsExport.uriFor(context, file),
            fileName = file.name,
            byteCount = file.length(),
        )
    }

    private companion object {
        const val EXPORT_FAILED: String =
            "Could not write the log to a file. This device may be out of space."

        const val CLEARED: String =
            "Cleared. Anything Needler does from now on will be logged here again."
    }
}
