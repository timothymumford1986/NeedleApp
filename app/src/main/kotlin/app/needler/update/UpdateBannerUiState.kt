package app.needler.update

/**
 * Everything the update banner draws, as one value.
 *
 * The banner is stateless for the same reason every other screen in this project is — the pattern
 * `:feature:library` sets and `ConnectScreen` follows: a composable handed a literal state and a
 * handful of callbacks can be rendered, asserted on and reasoned about without Hilt, a repository,
 * a network or a package installer, none of which a unit test can conjure.
 *
 * The strings live here rather than in the composable because they *are* the state: there is one
 * line of text on this bar and which line it is is the whole design. Putting them in a `when` inside
 * the layout would hide the product decision inside the painting; `LibraryUiState.headerLine` does
 * the same thing for the same reason.
 *
 * ## Why this is the subtlest thing the app draws
 *
 * The listener did not ask about updates. Nothing here is urgent, nothing here is broken, and the
 * app works perfectly well if they ignore it for a month. So the bar is one line, it sits out of the
 * way at the bottom of the chrome, it carries exactly one action and one dismissal, and when there
 * is nothing to say it is not a quiet empty bar — it is absent, and takes no height at all. See
 * [visible], which is what the Route leans on to compose nothing.
 */
data class UpdateBannerUiState(
    val phase: UpdatePhase = UpdatePhase.None,
    /** The version as a human reads it, `1.2.3`, never a `versionCode`. */
    val versionName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {

    /** False for all but a few minutes in the life of an install. Draws nothing at all. */
    val visible: Boolean get() = phase != UpdatePhase.None

    /**
     * The one line.
     *
     * Plain, short, and never exclamatory. "Needler 1.2.3 is available" states a fact; "Update
     * now!" would be an app with an opinion about how someone should spend the next two minutes.
     */
    val message: String
        get() = when (phase) {
            UpdatePhase.None -> ""
            UpdatePhase.Available -> "Needler $versionName is available"
            UpdatePhase.Downloading -> "Downloading $versionName"
            UpdatePhase.PermissionRequired -> "Allow Needler to install updates"
            UpdatePhase.AwaitingConfirmation -> "Confirm the install to finish"
            UpdatePhase.Installing -> "Installing $versionName"
            UpdatePhase.Failed -> "Needler $versionName could not be installed"
        }

    /**
     * The action, or `null` when there is nothing useful to press.
     *
     * "Retry" rather than anything about uninstalling, reinstalling or clearing data. The listener's
     * existing install is untouched by a failed update and must stay that way — the reasoning is at
     * the top of [ApkInstaller], and this label is where it reaches the screen.
     */
    val actionLabel: String?
        get() = when (phase) {
            UpdatePhase.Available -> "Update"
            UpdatePhase.PermissionRequired -> "Allow"
            UpdatePhase.Failed -> "Retry"
            UpdatePhase.None -> null
            UpdatePhase.Downloading -> null
            UpdatePhase.AwaitingConfirmation -> null
            UpdatePhase.Installing -> null
        }

    /** The progress bar only appears while bytes are moving. */
    val showProgress: Boolean get() = phase == UpdatePhase.Downloading

    /**
     * 0f to 1f.
     *
     * The asset size always comes from GitHub, so this is never indeterminate — which is why there
     * is no spinner anywhere in this feature. A determinate bar over a known three megabytes tells
     * the listener when it will be done; a spinner tells them only that something is happening.
     */
    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    /**
     * Whether the bar can be waved away.
     *
     * Everything is dismissible except an install already handed to the platform. Once the session
     * is committed the app is no longer the one in charge, and a dismiss control that could not
     * actually stop anything would be a lie.
     */
    val dismissible: Boolean
        get() = phase != UpdatePhase.Installing && phase != UpdatePhase.AwaitingConfirmation
}

/** The states the bar has a line for. Deliberately fewer than [UpdateState] has. */
enum class UpdatePhase {
    /** Nothing to say. The bar is absent, not empty. */
    None,
    Available,
    Downloading,

    /** Install-unknown-apps has not been granted for this package. */
    PermissionRequired,

    /** The platform's own confirmation dialogue is up, over the app. */
    AwaitingConfirmation,
    Installing,

    /**
     * One phase for both a broken download and a refused install.
     *
     * The listener's options are identical — press Retry, or ignore it — and telling them which
     * half of a process they did not know was two halves failed would be detail for its own sake.
     */
    Failed,
}

/**
 * Fold the repository's state into the banner's.
 *
 * A free function rather than a method on either type: [UpdateState] belongs to the update
 * machinery and [UpdateBannerUiState] to the screen, and the mapping between them is the seam. It
 * being a pure function of one argument is also what makes every row of the table above testable in
 * one line.
 */
fun UpdateState.toBannerState(): UpdateBannerUiState = when (this) {
    UpdateState.Idle -> UpdateBannerUiState()

    is UpdateState.Available -> UpdateBannerUiState(
        phase = UpdatePhase.Available,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )

    is UpdateState.Downloading -> UpdateBannerUiState(
        phase = UpdatePhase.Downloading,
        versionName = update.versionName,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )

    is UpdateState.PermissionRequired -> UpdateBannerUiState(
        phase = UpdatePhase.PermissionRequired,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )

    is UpdateState.AwaitingConfirmation -> UpdateBannerUiState(
        phase = UpdatePhase.AwaitingConfirmation,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )

    is UpdateState.Installing -> UpdateBannerUiState(
        phase = UpdatePhase.Installing,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )

    is UpdateState.DownloadFailed -> UpdateBannerUiState(
        phase = UpdatePhase.Failed,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )

    is UpdateState.InstallFailed -> UpdateBannerUiState(
        phase = UpdatePhase.Failed,
        versionName = update.versionName,
        totalBytes = update.sizeBytes,
    )
}
