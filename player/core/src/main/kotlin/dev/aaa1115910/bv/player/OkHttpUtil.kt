package dev.aaa1115910.bv.player

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object OkHttpUtil {
    @Volatile
    private var sharedClient: OkHttpClient? = null

    /**
     * Builds the custom-trust playback client once and reuses its dispatcher and connection pool.
     * Creating one client per player leaves multiple pools and threads alive while users move
     * through videos, which is particularly expensive on low-memory Android TV devices.
     */
    fun generateCustomSslOkHttpClient(context: Context): OkHttpClient {
        sharedClient?.let { return it }

        return synchronized(this) {
            sharedClient ?: buildCustomSslOkHttpClient(context.applicationContext).also {
                sharedClient = it
            }
        }
    }

    private fun buildCustomSslOkHttpClient(context: Context): OkHttpClient {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val customCaMap = mapOf(
            "custom:r5" to "GlobalSign ECC Root CA R5.crt"
        )

        val keyStoreType = KeyStore.getDefaultType()
        val systemKeyStore = KeyStore.getInstance("AndroidCAStore").apply {
            load(null, null)
        }
        val customKeyStore = KeyStore.getInstance(keyStoreType).apply {
            load(null, null)

            systemKeyStore.aliases().toList().forEach { alias ->
                setCertificateEntry(alias, systemKeyStore.getCertificate(alias))
            }
            customCaMap.forEach { (alias, caFilename) ->
                context.assets.open(caFilename).use { certificateInputStream ->
                    val certificate = certificateFactory.generateCertificate(certificateInputStream)
                    setCertificateEntry(alias, certificate)
                }
            }
        }

        val trustManagerFactory = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(customKeyStore) }
        val trustManager = trustManagerFactory.trustManagers[0] as X509TrustManager

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }
}
