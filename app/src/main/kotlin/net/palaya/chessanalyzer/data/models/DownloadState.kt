package net.palaya.chessanalyzer.data.models

/**
 * Why a download stopped without failing. The part file is always kept: [USER] is the Pause button
 * (or the process going away), [CONNECTION_LOST] and [SERVER_UNAVAILABLE] are
 * [DownloadStateMachine.MAX_TRANSIENT_FAILURES] transient failures in a row whose last one was a
 * dropped connection or a 5xx/429 answer respectively (the Setup screen words them differently,
 * design §1.3). Nothing resumes on its own: the user taps Resume.
 */
enum class PauseReason { USER, CONNECTION_LOST, SERVER_UNAVAILABLE }

/** Why a download ended for good (until the user taps Try again). */
enum class FailureReason {
    /** 404/410: the release does not have the file (wrong tag, or not published yet). */
    NOT_FOUND,

    /** Another 4xx, too many redirects, or a protocol answer the downloader cannot use. */
    SERVER,

    /** The server's size (Content-Length / Content-Range / bytes sent) differs from the pin. */
    SIZE_MISMATCH,

    /** The SHA-256 differed from the pin twice in a row, or the installed archive is broken. */
    DAMAGED,

    /** The URL or a redirect is not https (and not the debug-only loopback exception). */
    INSECURE,

    /** Not enough free space before the file, or ENOSPC while writing it. */
    INSUFFICIENT_STORAGE,

    /** The verified file could not be installed (moved / unpacked). */
    INSTALL,
}

/**
 * One file's download, as a pure state (docs/MODEL_DOWNLOAD_DESIGN.md §2.3). The counters ride in the
 * states, so [DownloadStateMachine] holds no hidden fields and every edge is testable on the host.
 */
sealed interface DownloadState {
    /** True for the states a download ends in: [Installed] and [Failed]. [Paused] is resumable. */
    val isTerminal: Boolean get() = false

    data object Idle : DownloadState

    /** Opening the connection (and following redirects). */
    data class Connecting(val transientFailures: Int, val hashFailures: Int) : DownloadState

    /** A 200/206 arrived and bytes are flowing into the part file. */
    data class Streaming(val transientFailures: Int, val hashFailures: Int) : DownloadState

    /** Waiting [delayMs] before the next attempt, after [transientFailures] failures in a row. */
    data class Backoff(val transientFailures: Int, val hashFailures: Int, val delayMs: Long) : DownloadState

    /** The whole file arrived but its SHA-256 was wrong; the part is deleted and it starts again once. */
    data class HashFailed(val hashFailures: Int) : DownloadState

    /** Size and SHA-256 match the pin; the part is ready for its store. */
    data object Verified : DownloadState

    /** The store is moving / unpacking the verified file ("Unpacking the voice…"). */
    data object Installing : DownloadState

    data object Installed : DownloadState {
        override val isTerminal: Boolean get() = true
    }

    data class Paused(val reason: PauseReason) : DownloadState

    data class Failed(val reason: FailureReason) : DownloadState {
        override val isTerminal: Boolean get() = true
    }
}

/** What happened to a download. Sent by [ModelDownloader] / `ModelSetup`, never by the UI directly. */
sealed interface DownloadEvent {
    /** The user's Download / Resume / Try again tap: counters start from zero. */
    data object Start : DownloadEvent

    /** A usable 200/206 answer: bytes will follow. */
    data object Connected : DownloadEvent

    /**
     * A failure worth retrying: a connection error, a timeout, a body that ended early, a 5xx/429
     * ([serverError] true), a 416. [retryAfterMs] is a server's Retry-After of at most 60 s.
     */
    data class TransientError(val serverError: Boolean = false, val retryAfterMs: Long? = null) : DownloadEvent

    data object BackoffElapsed : DownloadEvent

    /** The last byte arrived; [hashOk] is whether size and SHA-256 matched the pin. */
    data class EndOfFile(val hashOk: Boolean) : DownloadEvent

    data object RetryAfterHashFailure : DownloadEvent

    data class Fatal(val reason: FailureReason) : DownloadEvent

    /** The user's Pause (or the work being cancelled for any reason other than Cancel). */
    data object UserPause : DownloadEvent

    /** The user's Cancel: the part is deleted and the file starts from nothing next time. */
    data object Cancel : DownloadEvent

    data object InstallStarted : DownloadEvent
    data object InstallSucceeded : DownloadEvent
    data class InstallFailed(val reason: FailureReason = FailureReason.INSTALL) : DownloadEvent
}

/** The next state, and whether the part file must be deleted on the way. */
data class Transition(val state: DownloadState, val deletePart: Boolean = false)

/**
 * The transitions of design §2.3, pure. One instance per file download; [state] is the current state.
 *
 * Rules worth stating (each has a test in `DownloadStateMachineTest`):
 *  - transient failures back off 2, 4, 8, 16 s; the 5th in a row pauses ([PauseReason.CONNECTION_LOST]
 *    or [PauseReason.SERVER_UNAVAILABLE] after the last cause); a server Retry-After ≤ 60 s replaces
 *    the backoff delay;
 *  - a wrong hash deletes the part and restarts once by itself; the second wrong hash is
 *    [FailureReason.DAMAGED];
 *  - [DownloadEvent.Start] (Resume / Try again) resets both counters;
 *  - pausing never deletes the part; cancelling always does, from any non-terminal state;
 *  - a size mismatch is fatal and deletes the part (the pin is wrong or the server lies); other fatal
 *    answers keep it, so a later Try again can still resume.
 * An event that makes no sense in the current state is a bug in the caller: [IllegalStateException].
 */
class DownloadStateMachine(initial: DownloadState = DownloadState.Idle) {

    var state: DownloadState = initial
        private set

    fun on(event: DownloadEvent): Transition {
        val t = next(state, event)
        state = t.state
        return t
    }

    companion object {
        const val MAX_TRANSIENT_FAILURES = 5
        const val MAX_HASH_FAILURES = 2

        /** Backoff before attempt n+1 after n failures (n = 1..4); the 5th failure pauses instead. */
        val BACKOFF_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L)

        /** The longest server Retry-After honoured as the delay; a longer one is an ordinary backoff. */
        const val MAX_RETRY_AFTER_MS = 60_000L

        fun next(state: DownloadState, event: DownloadEvent): Transition {
            fun invalid(): Nothing = throw IllegalStateException("event $event is not valid in state $state")
            return when (event) {
                DownloadEvent.Start -> when (state) {
                    DownloadState.Idle, is DownloadState.Paused, is DownloadState.Failed ->
                        Transition(DownloadState.Connecting(transientFailures = 0, hashFailures = 0))
                    else -> invalid()
                }
                DownloadEvent.Connected -> when (state) {
                    is DownloadState.Connecting -> Transition(DownloadState.Streaming(state.transientFailures, state.hashFailures))
                    else -> invalid()
                }
                is DownloadEvent.TransientError -> {
                    val (transient, hash) = when (state) {
                        is DownloadState.Connecting -> state.transientFailures to state.hashFailures
                        is DownloadState.Streaming -> state.transientFailures to state.hashFailures
                        else -> invalid()
                    }
                    val failures = transient + 1
                    if (failures >= MAX_TRANSIENT_FAILURES) {
                        val reason = if (event.serverError) PauseReason.SERVER_UNAVAILABLE else PauseReason.CONNECTION_LOST
                        Transition(DownloadState.Paused(reason))
                    } else {
                        val delay = event.retryAfterMs?.takeIf { it in 0..MAX_RETRY_AFTER_MS }
                            ?: BACKOFF_DELAYS_MS[failures - 1]
                        Transition(DownloadState.Backoff(failures, hash, delay))
                    }
                }
                DownloadEvent.BackoffElapsed -> when (state) {
                    is DownloadState.Backoff -> Transition(DownloadState.Connecting(state.transientFailures, state.hashFailures))
                    else -> invalid()
                }
                is DownloadEvent.EndOfFile -> when (state) {
                    is DownloadState.Streaming -> when {
                        event.hashOk -> Transition(DownloadState.Verified)
                        state.hashFailures + 1 >= MAX_HASH_FAILURES ->
                            Transition(DownloadState.Failed(FailureReason.DAMAGED), deletePart = true)
                        else -> Transition(DownloadState.HashFailed(state.hashFailures + 1), deletePart = true)
                    }
                    else -> invalid()
                }
                DownloadEvent.RetryAfterHashFailure -> when (state) {
                    // A fresh start of the same file: connection counters reset, the hash count stays.
                    is DownloadState.HashFailed -> Transition(DownloadState.Connecting(0, state.hashFailures))
                    else -> invalid()
                }
                is DownloadEvent.Fatal -> when (state) {
                    is DownloadState.Connecting, is DownloadState.Streaming, DownloadState.Idle -> Transition(
                        DownloadState.Failed(event.reason),
                        deletePart = event.reason == FailureReason.SIZE_MISMATCH || event.reason == FailureReason.DAMAGED,
                    )
                    else -> invalid()
                }
                DownloadEvent.UserPause -> when (state) {
                    is DownloadState.Connecting, is DownloadState.Streaming, is DownloadState.Backoff,
                    is DownloadState.HashFailed, DownloadState.Verified, DownloadState.Installing ->
                        Transition(DownloadState.Paused(PauseReason.USER))
                    is DownloadState.Paused -> Transition(state)
                    else -> invalid()
                }
                DownloadEvent.Cancel -> if (state.isTerminal) invalid() else Transition(DownloadState.Idle, deletePart = true)
                DownloadEvent.InstallStarted -> when (state) {
                    DownloadState.Verified -> Transition(DownloadState.Installing)
                    else -> invalid()
                }
                DownloadEvent.InstallSucceeded -> when (state) {
                    DownloadState.Installing -> Transition(DownloadState.Installed)
                    else -> invalid()
                }
                is DownloadEvent.InstallFailed -> when (state) {
                    DownloadState.Installing, DownloadState.Verified -> Transition(DownloadState.Failed(event.reason), deletePart = true)
                    else -> invalid()
                }
            }
        }
    }
}
