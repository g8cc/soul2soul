package com.soul2soul.app.util

import android.util.Log
import com.soul2soul.app.App
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Let's Encrypt 根证书（ISRG Root X1）在 Android 7.0 系统信任库中不存在（7.1.1 才内置），
 * 而 Network Security Config 只支持 API 24+。要覆盖 Android 6.0，必须用代码级信任链：
 * 系统信任库 + 内置 LE 根证书 组合成复合 TrustManager。
 */
object TlsTrust {

    fun apply(builder: OkHttpClient.Builder, pemResId: Int) {
        runCatching {
            val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            systemTmf.init(null as KeyStore?)
            val system = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return

            val cf = CertificateFactory.getInstance("X.509")
            val pem = App.instance.resources.openRawResource(pemResId).use { it.readBytes() }
            val ca = cf.generateCertificate(pem.inputStream())
            val leStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg-root-x1", ca)
            }
            val leTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            leTmf.init(leStore)
            val le = leTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return

            // 复合校验：先走系统信任库，失败再尝试内置 LE 根（覆盖 Android 6/7.0）
            val composite = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
                    try {
                        system.checkClientTrusted(chain, authType)
                    } catch (e: Exception) {
                        le.checkClientTrusted(chain, authType)
                    }
                }

                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        le.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    system.acceptedIssuers + le.acceptedIssuers
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(composite), null)
            builder.sslSocketFactory(sslContext.socketFactory, composite)
            Log.d("TlsTrust", "LE root trust installed")
        }.onFailure { Log.w("TlsTrust", "trust setup failed", it) }
    }
}
