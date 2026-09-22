package app.needler.connect

import app.needler.core.network.ProxyCredentialStore
import app.needler.core.network.ProxyCredentials

/**
 * An in-memory [ProxyCredentialStore], so the Connect screen's tests can assert on what would have
 * been written to the encrypted store without going near Keystore.
 *
 * [writeSucceeds] exists because a failed credential write is a real branch on this screen: the
 * headers have to be on disk before the first request goes out, and a write that silently failed
 * would produce a connect attempt that is intercepted for no visible reason.
 */
internal class FakeProxyCredentialStore(
    private var saved: ProxyCredentials = ProxyCredentials.None,
    var writeSucceeds: Boolean = true,
) : ProxyCredentialStore {

    /** Every set of credentials handed to [saveProxyCredentials], in order. */
    val writes: MutableList<ProxyCredentials> = mutableListOf()

    override fun proxyCredentials(): ProxyCredentials = saved

    override fun saveProxyCredentials(credentials: ProxyCredentials): Boolean {
        writes += credentials
        if (!writeSucceeds) return false
        saved = credentials
        return true
    }
}
