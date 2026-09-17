package app.needler.core.network.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * The set of leaf certificates the user has explicitly confirmed, keyed by host.
 *
 * REQUIREMENTS.md §"Self-signed certificates": pin the leaf certificate for **one host**, never
 * disable validation globally, and fail loudly when the fingerprint changes. This store is the
 * only place that grants an exception, and it is scoped per host, so a pin for
 * `music.yourhome.net` can never make any other server's certificate acceptable.
 *
 * Implementations must be safe to read from OkHttp's threads during a TLS handshake.
 */
public interface CertificatePinStore {

    /** SHA-256 pins, base64, for [host] — empty when the user has pinned nothing for it. */
    public fun pinsFor(host: String): Set<String>

    /** True when [certificate] is one of the leaves the user confirmed for [host]. */
    public fun matches(host: String, certificate: X509Certificate): Boolean =
        pinsFor(host).contains(sha256Base64(certificate))

    public companion object {

        /** An empty, immutable store: platform validation only. */
        public val Empty: CertificatePinStore = object : CertificatePinStore {
            override fun pinsFor(host: String): Set<String> = emptySet()
        }

        /** Canonical host key: lower-case, trailing dot and brackets removed. */
        public fun normaliseHost(host: String): String =
            host.trim().removeSurrounding("[", "]").removeSuffix(".").lowercase()

        /**
         * Base64 of the SHA-256 over the whole DER-encoded leaf certificate.
         *
         * Deliberately over the certificate, not the public key: self-hosted servers regenerate
         * a self-signed certificate rather than re-key, and the doc wants a changed certificate
         * to fail loudly and require re-confirmation.
         *
         * `java.util.Base64` exists from API 26, which is Needler's minimum.
         */
        public fun sha256Base64(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return Base64.getEncoder().encodeToString(digest)
        }

        /** Colon-separated uppercase hex of the leaf's SHA-256, the form shown to the user. */
        public fun sha256Hex(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString(":") { byte -> "%02X".format(byte) }
        }
    }
}

/** What the Connect screen shows before asking the user to trust a certificate. */
public data class CertificateDetails(
    public val host: String,
    /** `SHA-256 AA:BB:…`, the value the user compares against their server. */
    public val sha256Hex: String,
    /** Base64 pin, the value handed to [MutableCertificatePinStore.pin]. */
    public val sha256Base64: String,
    public val subject: String,
    public val issuer: String,
    public val notBefore: Date,
    public val notAfter: Date,
    public val isSelfSigned: Boolean,
) {
    public val isExpired: Boolean get() = Date().after(notAfter)
    public val isNotYetValid: Boolean get() = Date().before(notBefore)

    public companion object {
        public fun of(host: String, certificate: X509Certificate): CertificateDetails =
            CertificateDetails(
                host = CertificatePinStore.normaliseHost(host),
                sha256Hex = CertificatePinStore.sha256Hex(certificate),
                sha256Base64 = CertificatePinStore.sha256Base64(certificate),
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
 * `:core:data` owns persistence (the pin belongs beside the saved server, and per
 * REQUIREMENTS.md §Security it must not leave the device); it calls [replaceAll] on startup and
 * [pin] when the user accepts a certificate.
 */
public class MutableCertificatePinStore(
    initial: Map<String, Set<String>> = emptyMap(),
) : CertificatePinStore {

    private val pins = AtomicReference(
        initial.entries.associate { (host, values) ->
            CertificatePinStore.normaliseHost(host) to values.toSet()
        },
    )

    /** Listener fired whenever the pin set changes, so pooled connections can be evicted. */
    public var onChanged: (() -> Unit)? = null

    override fun pinsFor(host: String): Set<String> =
        pins.get()[CertificatePinStore.normaliseHost(host)].orEmpty()

    /** Trust exactly this leaf for this host, in addition to anything already pinned. */
    public fun pin(host: String, sha256Base64: String) {
        val key = CertificatePinStore.normaliseHost(host)
        while (true) {
            val current = pins.get()
            val updated = current.toMutableMap()
            updated[key] = updated[key].orEmpty() + sha256Base64
            if (pins.compareAndSet(current, updated)) break
        }
        onChanged?.invoke()
    }

    /** Trust exactly this certificate for this host. */
    public fun pin(details: CertificateDetails): Unit = pin(details.host, details.sha256Base64)

    /** Drop every pin for one host — used when the server identity changes. */
    public fun clear(host: String) {
        val key = CertificatePinStore.normaliseHost(host)
        while (true) {
            val current = pins.get()
            if (!current.containsKey(key)) return
            if (pins.compareAndSet(current, current - key)) break
        }
        onChanged?.invoke()
    }

    /** Replace the whole store, e.g. when restoring persisted pins at startup. */
    public fun replaceAll(newPins: Map<String, Set<String>>) {
        pins.set(
            newPins.entries.associate { (host, values) ->
                CertificatePinStore.normaliseHost(host) to values.toSet()
            },
        )
        onChanged?.invoke()
    }

    /** Snapshot for persistence. */
    public fun snapshot(): Map<String, Set<String>> = pins.get()
}
