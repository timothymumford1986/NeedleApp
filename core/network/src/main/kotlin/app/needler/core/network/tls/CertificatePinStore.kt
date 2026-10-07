package app.needler.core.network.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * The set of leaf certificates the user has explicitly confirmed, keyed by host.
 *
 * REQUIREMENTS.md "Self-signed certificates": pin the leaf certificate for **one host**, never
 * disable validation globally, and fail loudly when the fingerprint changes. This store is the
 * only place that grants an exception, and it is scoped per host, so a pin for
 * `music.yourhome.net` can never make any other server's certificate acceptable.
 *
 * ## One encoding, converted in one place
 *
 * A pin is the **colon-separated uppercase hex** of the SHA-256 over the DER leaf -
 * `22:2D:19:...` - everywhere: in this store, in the `CertificateInfo` the Connect screen renders,
 * in the `CertificateChanged` error that demands re-confirmation, and in the one value
 * `SecureCredentialStore` persists. [sha256Hex] is the only function that turns a certificate into
 * that string, and nothing else converts.
 *
 * It was base64 here and hex everywhere else, and that was one of the three reasons "Trust this
 * certificate" did nothing: a pin written as hex could never equal a pin compared as base64, so
 * [matches] answered false for the very certificate the user had just confirmed.
 *
 * Base64 is the conventional form - it is what OkHttp's own `CertificatePinner` takes - and it was
 * rejected deliberately. Needler does not use `CertificatePinner`; [PinnedHostTrustManager] is its
 * own, so there is no interoperability to buy, and base64 would have had to be converted back to
 * hex for the two places that must show the fingerprint to a user comparing it by eye against
 * their server. Hex is already three of the four renderings, and the fourth was the bug. Base64's
 * one real advantage - a single unambiguous spelling - is covered by [normalisePin].
 *
 * Implementations must be safe to read from OkHttp's threads during a TLS handshake.
 */
public interface CertificatePinStore {

    /**
     * SHA-256 pins for [host], in the canonical hex of [sha256Hex] - empty when the user has
     * pinned nothing for it.
     *
     * Empty is the normal answer. A server with a certificate from a real CA passes platform
     * validation and this store is never consulted for it.
     */
    public fun pinsFor(host: String): Set<String>

    /**
     * True when [certificate] is one of the leaves the user confirmed for [host].
     *
     * Each stored value goes through [normalisePin] before the comparison rather than being
     * trusted to be canonical already. That costs a few string operations on a path that only runs
     * once platform validation has **already** failed, and it buys the property this interface
     * exists to guarantee: a pin written in any spelling of the same 32 bytes matches, and no
     * implementation of [pinsFor] can reintroduce the encoding mismatch that made trusting a
     * certificate a no-op.
     */
    public fun matches(host: String, certificate: X509Certificate): Boolean {
        val presented: String = sha256Hex(certificate)
        return pinsFor(host).any { stored -> normalisePin(stored) == presented }
    }

    public companion object {

        /** An empty, immutable store: platform validation only. */
        public val Empty: CertificatePinStore = object : CertificatePinStore {
            override fun pinsFor(host: String): Set<String> = emptySet()
        }

        /** Canonical host key: lower-case, trailing dot and brackets removed. */
        public fun normaliseHost(host: String): String =
            host.trim().removeSurrounding("[", "]").removeSuffix(".").lowercase()

        /**
         * Colon-separated uppercase hex of the SHA-256 over the whole DER-encoded leaf: the one
         * canonical rendering of a pin, and the one the user is shown.
         *
         * Deliberately over the certificate, not the public key: self-hosted servers regenerate a
         * self-signed certificate rather than re-key, and the doc wants a changed certificate to
         * fail loudly and require re-confirmation. A public-key pin would survive a reissue
         * silently, which is the opposite of what REQUIREMENTS.md asks for.
         */
        public fun sha256Hex(certificate: X509Certificate): String {
            val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString(":") { byte -> "%02X".format(byte) }
        }

        /**
         * The canonical spelling of an already-computed fingerprint.
         *
         * Accepts the renderings a fingerprint is written in by hand or by another tool - lower
         * case, no separators, spaces, dashes - and returns the one form [sha256Hex] produces, so
         * a pin typed or migrated in any of them still matches the certificate it names.
         *
         * Anything that is not exactly [SHA256_HEX_DIGITS] hex digits comes back merely trimmed
         * and upper-cased, which cannot match a real certificate. That is the safe direction: a
         * value this function does not understand must deny access, never grant it.
         */
        public fun normalisePin(raw: String): String {
            val digits = StringBuilder(SHA256_HEX_DIGITS)
            for (character in raw) {
                if (isHexDigit(character)) digits.append(character.uppercaseChar())
            }
            if (digits.length != SHA256_HEX_DIGITS) return raw.trim().uppercase()
            return digits.toString().chunked(2).joinToString(":")
        }

        /** Hex digits in a SHA-256: 32 bytes, two characters each. */
        public const val SHA256_HEX_DIGITS: Int = 64

        private fun isHexDigit(character: Char): Boolean =
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
}

/**
 * What the Connect screen shows before asking the user to trust a certificate: the fingerprint,
 * subject and expiry REQUIREMENTS.md "Self-signed certificates" requires it to show.
 */
public data class CertificateDetails(
    public val host: String,
    /**
     * `22:2D:19:...`, the value the user compares against their server **and** the value that is
     * pinned and persisted. There is deliberately no second encoding of it on this class: two
     * renderings of one fingerprint is what stopped a confirmed pin from ever matching.
     */
    public val sha256Hex: String,
    public val subject: String,
    public val issuer: String,
    public val notBefore: Date,
    public val notAfter: Date,
    public val isSelfSigned: Boolean,
) {
    public companion object {
        /** Reads [certificate] into the five things the user is entitled to see before deciding. */
        public fun of(host: String, certificate: X509Certificate): CertificateDetails =
            CertificateDetails(
                host = CertificatePinStore.normaliseHost(host),
                sha256Hex = CertificatePinStore.sha256Hex(certificate),
                subject = certificate.subjectX500Principal.name,
                issuer = certificate.issuerX500Principal.name,
                notBefore = certificate.notBefore,
                notAfter = certificate.notAfter,
                isSelfSigned = certificate.subjectX500Principal == certificate.issuerX500Principal,
            )
    }
}

/**
 * In-memory, copy-on-write pin store configured at runtime once the user confirms a fingerprint.
 *
 * `:core:data` owns persistence - the pin belongs beside the saved server, and per REQUIREMENTS.md
 * "Security" it must not leave the device - and it is the only caller: it seeds this store from the
 * credential store when the Hilt graph is built, calls [pin] when the user accepts a certificate,
 * and [clear] when the saved server's host changes. Both halves are needed. A pin written only to
 * the disk never reaches the handshake, and a pin held only here is gone on the next launch; each
 * of those was a separate reason "Trust this certificate" did nothing.
 */
public class MutableCertificatePinStore(
    initial: Map<String, Set<String>> = emptyMap(),
) : CertificatePinStore {

    private val pins: AtomicReference<Map<String, Set<String>>> =
        AtomicReference(normalise(initial))

    /**
     * Listener fired whenever the pin set changes, so pooled connections can be evicted.
     *
     * `NeedlerHttpClient` subscribes to it. Without that, a pin confirmed mid-session would not
     * take effect until the connection pool happened to recycle, so the user would tap Trust and
     * watch the same request fail - the bug this store exists to fix, one layer down.
     */
    public var onChanged: (() -> Unit)? = null

    override fun pinsFor(host: String): Set<String> =
        pins.get()[CertificatePinStore.normaliseHost(host)].orEmpty()

    /**
     * Trust exactly this leaf for this host, **replacing** anything previously pinned for it.
     *
     * Replacing rather than adding, and atomically, for two reasons. The persisted side holds one
     * fingerprint per host, so accumulating here would leave memory and disk disagreeing until the
     * next launch silently narrowed trust back. And a certificate that has changed must fail
     * loudly and be re-confirmed (REQUIREMENTS.md "Self-signed certificates"); keeping the
     * superseded leaf acceptable forever would mean the old certificate never failed again, which
     * is the loud failure quietly removed.
     *
     * This is not a silent replacement: the only caller is `SessionRepository.trustCertificate`,
     * which runs when the user has been shown the new fingerprint and tapped Trust. Nothing in
     * the TLS path can reach it.
     *
     * @param sha256Hex the fingerprint in any spelling - it is stored canonically.
     */
    public fun pin(host: String, sha256Hex: String) {
        val key: String = CertificatePinStore.normaliseHost(host)
        val value: Set<String> = setOf(CertificatePinStore.normalisePin(sha256Hex))
        while (true) {
            val current: Map<String, Set<String>> = pins.get()
            if (current[key] == value) return
            if (pins.compareAndSet(current, current + (key to value))) break
        }
        onChanged?.invoke()
    }

    /** Trust exactly this certificate for the host it was read from. */
    public fun pin(details: CertificateDetails): Unit = pin(details.host, details.sha256Hex)

    /**
     * Drop every pin for one host.
     *
     * Called when the saved server's host changes: a fingerprint the user confirmed for one server
     * says nothing about another, and leaving it behind is how a second host inherits an exception
     * nobody granted it.
     */
    public fun clear(host: String) {
        val key: String = CertificatePinStore.normaliseHost(host)
        while (true) {
            val current: Map<String, Set<String>> = pins.get()
            if (!current.containsKey(key)) return
            if (pins.compareAndSet(current, current - key)) break
        }
        onChanged?.invoke()
    }

    /**
     * Replace the whole store: the startup seed from persisted pins, and sign-out, which passes an
     * empty map.
     */
    public fun replaceAll(newPins: Map<String, Set<String>>) {
        pins.set(normalise(newPins))
        onChanged?.invoke()
    }

    /** Snapshot, for diagnostics and tests. Persistence is `:core:data`'s. */
    public fun snapshot(): Map<String, Set<String>> = pins.get()

    private fun normalise(source: Map<String, Set<String>>): Map<String, Set<String>> =
        source.entries
            .filter { (_, values) -> values.isNotEmpty() }
            .associate { (host, values) ->
                CertificatePinStore.normaliseHost(host) to
                    values.map(CertificatePinStore::normalisePin).toSet()
            }
}
