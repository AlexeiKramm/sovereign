package io.github.alexeikramm.sovereign.server

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * The phone's persisted, self-signed TLS identity (PROTOCOL.md §1, D-107, D-111).
 *
 * Generated once on first run and stored as a **PKCS12** file (not Android's default `BKS`, per the
 * Chunk 1b platform constraints). The certificate's SHA-256 **SPKI** fingerprint is what the desktop
 * pins after pairing and what the mDNS TXT record advertises, so both must be derived from the same
 * persisted certificate across runs.
 *
 * Pure JVM on purpose: no Android API, so the identity can be generated and its fingerprint checked
 * in a host unit test. The caller owns the directory (the service passes `filesDir`).
 *
 * X.509 self-signing needs BouncyCastle's PKIX builder — the Android platform exposes no public API
 * for it. The platform's own `Signature`/`CertificateFactory` do the crypto; only the DER ceremony is
 * BouncyCastle's.
 */
object ServerTls {

    /** File name inside the caller's directory. */
    const val FILE_NAME = "server-identity.p12"

    private const val ALIAS = "sovereign-server"

    /**
     * The PKCS12 password protecting the private key at rest. The file lives in the app's private
     * storage (`filesDir`, unreadable by other apps), so this is format binding rather than a secret.
     */
    private val DEFAULT_PASSWORD = "sovereign".toCharArray()

    private const val KEY_SIZE = 2048
    private const val VALID_DAYS = 3650L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** A loaded server identity: the TLS context to serve with and the fingerprint the client pins. */
    data class Identity(
        val sslContext: SSLContext,
        /** Base64url SHA-256 of the certificate's SubjectPublicKeyInfo (PROTOCOL.md §2 `spki`). */
        val spkiSha256: String,
        val certificate: X509Certificate,
    )

    /**
     * Loads the identity from `directory/[FILE_NAME]`, generating and persisting it on first run.
     * Throws if the file exists but cannot be read or the certificate is unusable — a fresh identity
     * would silently invalidate every desktop's pin, so that must be a hard failure, not a fallback.
     */
    fun loadOrCreate(directory: File, password: CharArray = DEFAULT_PASSWORD): Identity {
        val file = File(directory, FILE_NAME)
        val keyStore = KeyStore.getInstance("PKCS12")
        if (file.isFile) {
            file.inputStream().use { keyStore.load(it, password) }
        } else {
            keyStore.load(null, password)
            val (privateKey, certificate) = generate()
            keyStore.setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
            directory.mkdirs()
            file.outputStream().use { keyStore.store(it, password) }
        }
        return identityFrom(keyStore, password)
    }

    /** Base64url SHA-256 of [X509Certificate.getPublicKey]'s SubjectPublicKeyInfo encoding. */
    fun spkiSha256(certificate: X509Certificate): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    private fun generate(): Pair<PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()
        val subject = X500Name("CN=Sovereign Server")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - 60_000L),
            Date(now + VALID_DAYS * MILLIS_PER_DAY),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return keyPair.private to certificate
    }

    private fun identityFrom(keyStore: KeyStore, password: CharArray): Identity {
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        keyStore.getKey(ALIAS, password) as PrivateKey
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, null)
        }
        return Identity(sslContext, spkiSha256(certificate), certificate)
    }
}
