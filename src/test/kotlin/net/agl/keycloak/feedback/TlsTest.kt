package net.agl.keycloak.feedback

import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [buildSslContext] pins trust to specific PEM certs, so it's worth proving end-to-end against a
 * real TLS handshake rather than just unit-testing the KeyStore plumbing: a self-signed cert
 * generated with `keytool`, a loopback [SSLServerSocket] presenting it, and a client [SSLContext]
 * built from its exported PEM.
 */
class TlsTest {

    private lateinit var certDir: File
    private lateinit var keystorePath: File
    private lateinit var certPath: File
    private lateinit var serverSocket: SSLServerSocket
    private lateinit var serverThread: Thread

    @BeforeTest
    fun start() {
        certDir = createTempDirectory("tls-test").toFile()
        keystorePath = File(certDir, "server.jks")
        certPath = File(certDir, "server.pem")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath

        runKeytool(
            keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-keystore", keystorePath.path, "-storepass", "changeit",
            "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
        )
        runKeytool(
            keytool, "-exportcert", "-alias", "test", "-keystore", keystorePath.path,
            "-storepass", "changeit", "-rfc", "-file", certPath.path,
        )

        val serverKeyStore = KeyStore.getInstance("JKS").apply {
            keystorePath.inputStream().use { load(it, "changeit".toCharArray()) }
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeyStore, "changeit".toCharArray())
        }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, null, null) }
        serverSocket = serverContext.serverSocketFactory.createServerSocket(0, 1, InetSocketAddress("127.0.0.1", 0).address) as SSLServerSocket

        serverThread = Thread {
            runCatching { (serverSocket.accept() as SSLSocket).use { it.startHandshake() } }
        }.apply { isDaemon = true; start() }
    }

    private fun runKeytool(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "keytool failed ($exitCode): $output" }
    }

    @AfterTest
    fun stop() {
        runCatching { serverSocket.close() }
        certDir.deleteRecursively()
    }

    @Test
    fun `trusts a connection signed by a pinned certificate`() {
        val context = buildSslContext(listOf(certPath.path))
        val socket = context.socketFactory.createSocket("127.0.0.1", serverSocket.localPort) as SSLSocket

        socket.use { it.startHandshake() }
        serverThread.join(5_000)
    }

    @Test
    fun `rejects a connection whose certificate isn't in the pinned set`() {
        val otherCertDir = createTempDirectory("tls-test-other").toFile()
        try {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val otherKeystore = File(otherCertDir, "other.jks")
            val otherCert = File(otherCertDir, "other.pem")
            runKeytool(
                keytool, "-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-keystore", otherKeystore.path, "-storepass", "changeit",
                "-dname", "CN=someone-else",
            )
            runKeytool(
                keytool, "-exportcert", "-alias", "other", "-keystore", otherKeystore.path,
                "-storepass", "changeit", "-rfc", "-file", otherCert.path,
            )

            val context = buildSslContext(listOf(otherCert.path))
            val socket = context.socketFactory.createSocket("127.0.0.1", serverSocket.localPort) as SSLSocket

            assertFailsWith<Exception> { socket.use { it.startHandshake() } }
            assertTrue(true)
        } finally {
            otherCertDir.deleteRecursively()
        }
    }
}
