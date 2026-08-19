package net.agl.keycloak.feedback

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * TLS settings shared by the broker sinks (Kafka, MQTT, AMQP — webhook already gets this from its
 * `url` scheme). [enabled] defaults to true; when a broker is deliberately unencrypted, set it to
 * false explicitly to silence the sink's plaintext warning. [trustedCertificates] are PEM file
 * paths: when non-empty, the connection trusts *only* these (pinning to a private/self-signed CA
 * on a closed network) instead of the JVM's default trust store.
 */
data class TlsConfig(
    val enabled: Boolean = true,
    val trustedCertificates: List<String> = emptyList(),
)

/** An [SSLContext] that trusts only the PEM certificates at [certificatePaths] — see [TlsConfig.trustedCertificates]. */
internal fun buildSslContext(certificatePaths: List<String>): SSLContext {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    val certificateFactory = CertificateFactory.getInstance("X.509")
    certificatePaths.forEachIndexed { i, path ->
        File(path).inputStream().use { keyStore.setCertificateEntry("cert-$i", certificateFactory.generateCertificate(it)) }
    }
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore) }
    return SSLContext.getInstance("TLS").apply { init(null, trustManagerFactory.trustManagers, null) }
}
