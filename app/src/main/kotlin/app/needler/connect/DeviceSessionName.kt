package app.needler.connect

import android.os.Build

/**
 * The name Needler registers its companion device session under.
 *
 * REQUIREMENTS.md "Authentication", rule 2: mint a companion session named
 * after the device, for example `Needler · Pixel 8`. Two constraints come with
 * that and both are handled here rather than left to the server:
 *
 *  - **The server caps the name at 80 characters after whitespace collapsing.**
 *    A long OEM model name must therefore be truncated client-side; leaving it
 *    to the server means onboarding fails at the last step on exactly the
 *    devices with the longest names.
 *  - **Minting under a name that already exists rotates it**, revoking the
 *    previous companion token. That is wanted when this device re-installs, and
 *    it is precisely why two devices must never end up with the same name - so
 *    the model is used as given rather than normalised into a family name.
 *
 * Kept as a pure function over its inputs so the truncation rule can be tested
 * without a device.
 */
internal object DeviceSessionName {

    /** The server's limit, from `POST /api/v1/auth/device-sessions`. */
    const val MAX_LENGTH: Int = 80

    private const val PREFIX = "Needler · "

    /** The name for the device this code is running on. */
    fun current(): String = of(manufacturer = Build.MANUFACTURER, model = Build.MODEL)

    /**
     * Builds the name from a manufacturer and model.
     *
     * The model is the whole name wherever there is one: `Build.MODEL` on a
     * Pixel 8 is literally "Pixel 8", which is the example REQUIREMENTS.md
     * gives, and prefixing the manufacturer would turn it into "Google Pixel
     * 8" - longer, and no more use in a list of sessions. The manufacturer is
     * a fallback for the handful of devices that report no model at all.
     */
    fun of(manufacturer: String?, model: String?): String {
        val device = collapse(model)
            .ifEmpty { collapse(manufacturer) }
            .ifEmpty { "Android device" }

        // Truncate the device part, never the product name: a session list full
        // of entries called "Needle" would be unreadable, and the prefix is what
        // tells the user which app the session belongs to.
        val room = MAX_LENGTH - PREFIX.length
        return PREFIX + device.take(room).trimEnd()
    }

    /** Collapses runs of whitespace, which is what the server does before measuring. */
    private fun collapse(value: String?): String =
        value.orEmpty().trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
