package de.serofernbedienung

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Akzeptiert das selbstsignierte Zertifikat des Fernsehers – aber nur
 *  - für Verbindungen zur gespeicherten Fernseher-IP (HostnameVerifier) und
 *  - nur dieses eine Zertifikat: Beim ersten Verbinden wird sein Fingerabdruck
 *    gespeichert, danach wird jedes andere Zertifikat abgelehnt.
 * Der Client wird ausschließlich für die Fernseher-Verbindung verwendet, nicht global.
 */
class PinnedTvTrustManager(private val prefs: Prefs) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Nicht unterstützt")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Kein Zertifikat")
        val fp = sha256(chain[0].encoded)
        val pin = prefs.certPin
        if (pin == null) {
            prefs.certPin = fp
        } else if (pin != fp) {
            throw CertificateException(
                "Zertifikat des Fernsehers hat sich geändert. Falls gewollt: Kopplung zurücksetzen."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

object TvHttp {
    fun client(prefs: Prefs): OkHttpClient {
        val tm = PinnedTvTrustManager(prefs)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { host, _ -> host == prefs.ip }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
