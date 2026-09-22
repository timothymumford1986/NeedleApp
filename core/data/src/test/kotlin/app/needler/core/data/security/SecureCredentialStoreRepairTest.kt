package app.needler.core.data.security

import android.content.SharedPreferences
import app.needler.core.network.ProxyCredentials
import app.needler.core.network.ProxyPresets
import app.needler.core.network.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the credential store does when a secret is rejected - the half of the repair decision that
 * lives below the domain.
 *
 * The rule: a rejected app-password takes the app-password and **nothing else**. The bearer is what
 * mints the replacement, so a store that dropped both would leave the app with no way to repair
 * itself, and one revoked secret - most often revoked from the web UI by a user who did not realise
 * which app it belonged to - would cost a sign-in, a full re-sync and a multi-gigabyte cache.
 *
 * The store is exercised over an in-memory [SharedPreferences] rather than the encrypted one: the
 * encryption is Jetpack's business, while the state machine above it is Needler's and is what breaks.
 */
public class SecureCredentialStoreRepairTest {

    private val preferences: FakeSharedPreferences = FakeSharedPreferences()

    private fun store(): SecureCredentialStore = SecureCredentialStore.createForTesting(preferences)

    private fun provisionedStore(): SecureCredentialStore = store().apply {
        assertTrue(saveServerUrl(ServerUrl.parseOrNull("https://music.example.net")!!))
        assertTrue(saveCompanionBearer("bearer-token", issuedAtMillis = 1_000L))
        assertTrue(saveAppPassword("app-password-secret"))
    }

    @Test
    public fun `a rejected app-password keeps the bearer, because the bearer is the repair`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        credentials.onAppPasswordRejected()

        assertNull(credentials.appPassword())
        assertNotNull(credentials.bearerToken())
        assertTrue(credentials.appPasswordRepairNeeded.value)
        // Nothing to tell the user about: the app has everything it needs to fix this itself.
        assertFalse(credentials.isReonboardingRequired())
        // The /api/v1 lane is untouched, so search, requests and the queue keep working throughout.
        assertFalse(credentials.sessionStale.value)
    }

    @Test
    public fun `playback pauses during the repair and resumes when the replacement lands`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        credentials.onAppPasswordRejected()
        // Streaming is on the Subsonic lane, so it cannot work without an app-password. This is the
        // pause the user sees as ordinary buffering.
        assertFalse(credentials.canPlay())

        assertTrue(credentials.saveAppPassword("minted-replacement"))

        assertTrue(credentials.canPlay())
        assertTrue(credentials.isFullyProvisioned())
        assertFalse(credentials.appPasswordRepairNeeded.value)
    }

    @Test
    public fun `repeated rejections are harmless, because many requests fail at once`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        repeat(5) { credentials.onAppPasswordRejected() }

        assertNotNull(credentials.bearerToken())
        assertTrue(credentials.appPasswordRepairNeeded.value)
    }

    @Test
    public fun `a repair interrupted by process death is resumed, not restarted`(): Unit {
        provisionedStore().onAppPasswordRejected()

        // A new store over the same file: bearer present, app-password gone.
        val restarted: SecureCredentialStore = store()

        assertTrue(restarted.appPasswordRepairNeeded.value)
        assertFalse(restarted.isReonboardingRequired())
        assertNotNull(restarted.bearerToken())
    }

    @Test
    public fun `only losing both secrets asks the user to sign in again`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        credentials.onAppPasswordRejected()
        assertFalse(credentials.isReonboardingRequired())

        credentials.onBearerRejected()

        assertTrue(credentials.isReonboardingRequired())
        assertTrue(credentials.sessionStale.value)
    }

    @Test
    public fun `an expired bearer alone still leaves a working music player`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        credentials.onBearerRejected()

        assertTrue(credentials.canPlay())
        assertFalse(credentials.isReonboardingRequired())
        assertFalse(credentials.appPasswordRepairNeeded.value)
    }

    @Test
    public fun `proxy headers live with the other secrets and survive a process death`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()

        assertTrue(
            credentials.saveProxyCredentials(
                ProxyPresets.cloudflareAccess("0123.access", "service-token-secret"),
            ),
        )

        assertEquals(2, credentials.proxyCredentials().headers.size)
        // A fresh store over the same file is what an interceptor sees after the process is
        // restarted: the headers have to come back, or a server behind a proxy becomes unreachable
        // on the next cold start.
        val reopened: SecureCredentialStore = store()
        assertEquals("0123.access", reopened.proxyCredentials().headers[0].value)
        assertEquals("service-token-secret", reopened.proxyCredentials().headers[1].value)
        // And nothing about the store renders a value.
        assertFalse(reopened.toString().contains("service-token-secret"))
        assertFalse(reopened.proxyCredentials().toString().contains("service-token-secret"))
    }

    @Test
    public fun `clearing the proxy headers removes them from disk`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()
        assertTrue(credentials.saveProxyCredentials(ProxyCredentials.of("X-Api-Key" to "k")))

        assertTrue(credentials.saveProxyCredentials(ProxyCredentials.None))

        assertTrue(credentials.proxyCredentials().isEmpty)
        assertTrue(store().proxyCredentials().isEmpty)
    }

    @Test
    public fun `signing out takes the proxy headers with everything else`(): Unit {
        val credentials: SecureCredentialStore = provisionedStore()
        assertTrue(credentials.saveProxyCredentials(ProxyCredentials.of("X-Api-Key" to "k")))

        assertTrue(credentials.clear())

        assertTrue(credentials.proxyCredentials().isEmpty)
    }

    @Test
    public fun `a server with no proxy in front of it stores nothing extra`(): Unit {
        assertTrue(provisionedStore().proxyCredentials().isEmpty)
        assertNull(preferences.getString("proxy_headers", null))
    }

    @Test
    public fun `an app with no server saved is not in a repair or a re-onboarding state`(): Unit {
        // A fresh install has no secrets either, and must not be mistaken for a broken one.
        val credentials: SecureCredentialStore = store()

        assertFalse(credentials.isReonboardingRequired())
        assertFalse(credentials.appPasswordRepairNeeded.value)
        assertEquals("SecureCredentialStore(provisioned=false)", credentials.toString())
    }
}

/**
 * An in-memory [SharedPreferences]. Only the operations the store uses are meaningful; the listener
 * and multi-process members are not part of the contract being tested.
 */
private class FakeSharedPreferences : SharedPreferences {

    private val values: MutableMap<String, Any?> = LinkedHashMap()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {

        private val pending: MutableMap<String, Any?> = LinkedHashMap()
        private val removals: MutableSet<String> = LinkedHashSet()
        private var clearAll: Boolean = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putStringSet(
            key: String,
            value: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
