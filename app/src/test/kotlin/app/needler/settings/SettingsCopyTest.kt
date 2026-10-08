package app.needler.settings

import app.needler.core.domain.model.PinnedCertificate
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three sentences on screen 12 that said the wrong thing, and the row that said nothing at all.
 *
 * All four are plain functions of a state or a report, so none of this renders: the faults were in
 * what the words were, not in where they were drawn, and a screenshot of a wrong sentence is a
 * perfectly sharp picture of a wrong sentence.
 */
class SettingsCopyTest {

    // ---- the switch that described its own opposite --------------------------

    /**
     * `Keep pulled albums on device` is off by default, and its subtitle used to describe the on-state
     * regardless: "Anything this device pulls is downloaded straight away." Read on a device with the
     * switch off, that is the app claiming to do something it is not doing.
     */
    @Test
    fun `the keep-pulled subtitle describes the state the switch is actually in`() {
        val off: String = storage(keepPulled = false).keepPulledAlbumsSubtitle
        val on: String = storage(keepPulled = true).keepPulledAlbumsSubtitle

        assertNotEquals(
            "the subtitle has to change with the switch, or it cannot confirm the tap landed",
            off,
            on,
        )
        assertTrue("the on-state should say downloads happen", on.contains("downloaded"))
        assertTrue("the off-state should say where the music stays", off.contains("on the server"))
        assertFalse(
            "the off-state must not claim anything is downloaded: " + off,
            off.contains("downloaded straight away"),
        )
    }

    // ---- the success that read as a failure ---------------------------------

    /**
     * `Synced 0 albums and 0 artists.` was the device's report of a sync that had nothing to do. Two
     * zeroes and a full stop is the shape of an error message.
     */
    @Test
    fun `a sync that changed nothing says so instead of counting to zero`() {
        val nothing = SyncReport(phase = SyncPhase.DELTA, libraryUnchanged = false)

        assertEquals("Already up to date.", SettingsNotices.synced(nothing))
    }

    /**
     * The cheap path, where the revision had not moved, reads the same.
     *
     * It already did before this change, which is exactly why the bug survived: the flag covers only
     * the pass that returned after one request, and the user hit the pass that ran and found nothing.
     */
    @Test
    fun `an unchanged revision says the same thing`() {
        val unchanged = SyncReport(phase = SyncPhase.DELTA, libraryUnchanged = true)

        assertEquals("Already up to date.", SettingsNotices.synced(unchanged))
    }

    /** A pass that wrote something still reports what, because that is the useful case. */
    @Test
    fun `a sync that changed something keeps its counts`() {
        val report = SyncReport(
            phase = SyncPhase.DELTA,
            albumsUpdated = 3,
            artistsUpdated = 1,
            tracksUpdated = 41,
        )

        assertEquals("Synced 3 albums and 1 artist.", SettingsNotices.synced(report))
    }

    /**
     * Tracks alone are enough to stop it claiming to be up to date.
     *
     * This cannot arise today - `LibrarySyncEngine` only ever counts tracks inside its per-album loop -
     * and it is asserted anyway, because "already up to date" would become a lie the day it can and
     * the two counts the sentence draws would not notice.
     */
    @Test
    fun `work with no album or artist behind it is not up to date`() {
        val report = SyncReport(phase = SyncPhase.FULL, tracksUpdated = 7)

        assertEquals("Synced 0 albums and 0 artists.", SettingsNotices.synced(report))
    }

    // ---- the certificate nobody could see -----------------------------------

    /**
     * The row answers the question in the negative too.
     *
     * A user asking "is this app trusting a certificate my browser would refuse" needs an answer when
     * the answer is no. A row drawn only when something is pinned cannot give one: its absence would be
     * indistinguishable from the app never saying, which is the state this fixes.
     */
    @Test
    fun `the certificate row reports None rather than disappearing`() {
        val server = ServerSectionState(
            host = "music.yourhome.net",
            certificateChecked = true,
        )

        assertTrue(server.showCertificateRow)
        assertEquals("None", server.trustedCertificateValue)
        assertNull(server.trustedCertificateFingerprint)
        assertFalse("there is nothing to forget", server.canForgetCertificate)
    }

    /** With one pinned, the row names the host and the line under it carries the whole fingerprint. */
    @Test
    fun `a pinned certificate names its host and shows its fingerprint in full`() {
        val server = ServerSectionState(
            host = "music.yourhome.net",
            certificateChecked = true,
            pinnedCertificate = PinnedCertificate(
                host = "music.yourhome.net",
                sha256Fingerprint = FINGERPRINT,
            ),
        )

        assertEquals("music.yourhome.net", server.trustedCertificateValue)
        assertEquals(
            "an abbreviated fingerprint is one the user cannot check against their server",
            FINGERPRINT,
            server.trustedCertificateFingerprint,
        )
        assertTrue(server.canForgetCertificate)
    }

    /**
     * The row waits until the pin has been read.
     *
     * "None" is the reassuring answer, and a reassuring answer that turns out to be wrong a frame later
     * is worse than a blank.
     */
    @Test
    fun `the row is not drawn before the pin has been read`() {
        assertFalse(ServerSectionState(host = "music.yourhome.net").showCertificateRow)
    }

    /** A fresh install has no server and therefore nothing it could have trusted. */
    @Test
    fun `the row is not drawn with no server configured`() {
        assertFalse(ServerSectionState(certificateChecked = true).showCertificateRow)
    }

    /**
     * The confirmation leads with the consequence, because the consequence is the reason for the second
     * tap: forgetting a pin is reversible, and it takes the server offline until it is reversed.
     */
    @Test
    fun `forgetting a certificate warns that the server goes unreachable`() {
        val prompt: String = DestructiveSettingsAction.ForgetCertificate.prompt

        assertTrue(prompt, prompt.contains("unreachable"))
        assertTrue(prompt, prompt.contains("Connect"))
        assertTrue(
            "it deletes no music, and should say so: " + prompt,
            prompt.contains("Nothing on this device is deleted"),
        )
    }

    // ---- the dead end on a fresh install -------------------------------------

    /**
     * `Change server` is the wrong verb when there is no server.
     *
     * On `settings-fresh-phone.png` it was also the *only* live control: the row above read
     * "No server" with nothing attached and Sync now was greyed, so the whole screen turned on one
     * word, and the word asked the user to change something they had not got.
     */
    @Test
    fun `the server action names what it will actually do`() {
        assertEquals("Connect a server", ServerSectionState().changeServerLabel)
        assertEquals(
            "Change server",
            ServerSectionState(host = "music.yourhome.net").changeServerLabel,
        )
    }

    /**
     * Sync now is absent with no server and present-but-greyed while a sync runs.
     *
     * Absent and disabled are different claims. "There is nothing here" is true of a fresh install;
     * "this is briefly unavailable" is true mid-sync, and the row above says `Syncing…` so the reason
     * is on screen. A greyed control with no reason is what the fresh-install golden showed.
     */
    @Test
    fun `sync now is removed with no server and greyed only while syncing`() {
        val fresh = ServerSectionState()
        assertFalse("nothing to sync against, so nothing to offer", fresh.showSyncNow)

        val idle = ServerSectionState(host = "music.yourhome.net")
        assertTrue(idle.showSyncNow)
        assertTrue(idle.canSyncNow)

        val busy = ServerSectionState(host = "music.yourhome.net", syncing = true)
        assertTrue("a sync in flight still draws the control", busy.showSyncNow)
        assertFalse(busy.canSyncNow)
    }

    // ---- the screen that collapsed two settings and said nothing --------------

    /**
     * The single `Stream quality` row says why it is single.
     *
     * `settings-no-transcoding-phone.png` dropped `Stream quality on Wi-Fi` and
     * `Stream quality on mobile data` for one chevron-less row reading `Original`, with no statement
     * that the server cannot re-encode - while the screen beside it spent four lines explaining a
     * caveat. The two sentences differ because the two causes do.
     */
    @Test
    fun `the collapsed stream-quality row explains which of its two causes applies`() {
        val refused = PlayingSectionState(
            transcodingAvailable = false,
            transcodingNegotiated = true,
        )
        val answer: String = refused.streamQualityStatement(serverConfigured = true)
        assertTrue(answer, answer.contains("cannot re-encode"))

        val fresh: String = refused.streamQualityStatement(serverConfigured = false)
        assertTrue(fresh, fresh.contains("Connect one"))
        assertNotEquals(
            "a server that refused and a server that does not exist are different facts",
            answer,
            fresh,
        )
    }

    private fun storage(keepPulled: Boolean): StorageSectionState =
        StorageSectionState(keepPulledAlbumsOnDevice = keepPulled)

    private companion object {
        /** 32 bytes, colon-separated uppercase hex, as `CertificatePinStore.sha256Hex` renders it. */
        const val FINGERPRINT: String =
            "22:2D:19:4A:5B:6C:7D:8E:9F:A0:B1:C2:D3:E4:F5:06:17:28:39:4A:5B:6C:7D:8E:9F:A0:B1:C2:" +
                "D3:E4:F5:06"
    }
}
