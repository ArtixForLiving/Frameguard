package com.optimizer.game.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

object AdbKeyManager {

    private const val KEY_DIR = "adb_keys"
    private const val PRIVATE_KEY_FILE = "adbkey"
    private const val PUBLIC_KEY_FILE = "adbkey.pub"
    private const val CERT_FILE = "adbkey.crt"

    private var cachedKeyPair: KeyPair? = null
    private var cachedCert: X509Certificate? = null

    @Synchronized
    fun resetKeys(context: Context) {
        cachedKeyPair = null
        cachedCert = null
        val keyDir = File(context.filesDir, KEY_DIR)
        File(keyDir, PRIVATE_KEY_FILE).delete()
        File(keyDir, PUBLIC_KEY_FILE).delete()
        File(keyDir, CERT_FILE).delete()
    }

    @Synchronized
    fun getKeyPair(context: Context): KeyPair {
        cachedKeyPair?.let { return it }

        val keyDir = File(context.filesDir, KEY_DIR).apply { mkdirs() }
        val privFile = File(keyDir, PRIVATE_KEY_FILE)
        val pubFile = File(keyDir, PUBLIC_KEY_FILE)
        val certFile = File(keyDir, CERT_FILE)

        if (privFile.exists() && certFile.exists()) {
            try {
                val privBytes = privFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))

                val certFact = CertificateFactory.getInstance("X.509")
                val cert = certFact.generateCertificate(FileInputStream(certFile)) as X509Certificate

                // Verify cryptographic match between private key and certificate
                val test = "adb_verify".toByteArray(Charsets.UTF_8)
                val sig = Signature.getInstance("SHA256withRSA").apply {
                    initSign(privKey)
                    update(test)
                }.sign()
                val verified = Signature.getInstance("SHA256withRSA").apply {
                    initVerify(cert.publicKey)
                    update(test)
                }.verify(sig)

                if (verified) {
                    val kp = KeyPair(cert.publicKey, privKey)
                    cachedKeyPair = kp
                    cachedCert = cert
                    return kp
                }
            } catch (_: Exception) {
                // Mismatch or corrupted, regenerate below
            }
        }

        // Generate clean 2048-bit RSA key pair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        // Generate self-signed X.509 certificate valid for 20 years
        val subject = X500Name("CN=FrameGuard,O=ArtixForLiving")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        // Save keys
        privFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(cert.encoded)
        pubFile.writeBytes(keyPair.public.encoded)

        cachedKeyPair = keyPair
        cachedCert = cert
        return keyPair
    }

    @Synchronized
    fun getCertificate(context: Context): X509Certificate {
        cachedCert?.let { return it }
        getKeyPair(context)
        return cachedCert!!
    }

    @Synchronized
    fun getPublicKeyBytes(context: Context): ByteArray {
        val cert = getCertificate(context)
        return cert.publicKey.encoded
    }
}
