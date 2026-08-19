package net.agl.keycloak.feedback

import com.sun.net.httpserver.HttpServer
import org.keycloak.events.Event
import org.keycloak.events.EventType
import java.net.InetSocketAddress
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Captures the headers a real [WebhookEventSink] request arrives with, via a loopback HTTP server. */
class WebhookEventSinkTest {

    private lateinit var server: HttpServer
    private lateinit var receivedHeaders: Map<String, List<String>>
    private lateinit var receivedBody: String

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedHeaders = exchange.requestHeaders
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/"

    private fun send(config: WebhookConfig, payloadConfig: PayloadConfig = PayloadConfig()) {
        WebhookEventSink(config, payloadConfig).use {
            it.send(Event().apply { type = EventType.LOGIN; ipAddress = "203.0.113.7" })
        }
    }

    @Test
    fun `sends the api-key under its configured header name`() {
        send(WebhookConfig(url(), apiKey = ApiKeyConfig("secret-1", "X-Custom-Key")))

        assertEquals(listOf("secret-1"), receivedHeaders["X-Custom-Key"])
        assertNull(receivedHeaders["X-Api-Key"])
    }

    @Test
    fun `authorization token defaults to a Bearer scheme`() {
        send(WebhookConfig(url(), authorization = AuthorizationConfig.Token("secret-2", "Bearer")))

        assertEquals(listOf("Bearer secret-2"), receivedHeaders["Authorization"])
    }

    @Test
    fun `authorization token with a null type sends the raw token, no scheme prefix`() {
        send(WebhookConfig(url(), authorization = AuthorizationConfig.Token("secret-2", null)))

        assertEquals(listOf("secret-2"), receivedHeaders["Authorization"])
    }

    @Test
    fun `authorization basic auth base64-encodes username and password`() {
        send(WebhookConfig(url(), authorization = AuthorizationConfig.Basic("user", "pass")))

        assertEquals(listOf("Basic dXNlcjpwYXNz"), receivedHeaders["Authorization"])
    }

    @Test
    fun `hmac signs the JSON body and applies the configured prefix`() {
        send(WebhookConfig(url(), hmac = HmacConfig("shared-secret", "X-Hub-Signature-256", "HmacSHA256", "sha256=")))

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("shared-secret".toByteArray(), "HmacSHA256"))
        val expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(receivedBody.toByteArray()))

        assertEquals(listOf(expected), receivedHeaders["X-Hub-Signature-256"])
    }

    @Test
    fun `explicit auth headers take precedence over a custom header of the same name`() {
        send(
            WebhookConfig(
                url(),
                headers = mapOf("X-Api-Key" to "from-custom-headers"),
                apiKey = ApiKeyConfig("from-api-key", "X-Api-Key"),
            ),
        )

        assertEquals(listOf("from-api-key"), receivedHeaders["X-Api-Key"])
    }

    @Test
    fun `body is the shaped payload, not the raw event, and PayloadConfig actually reaches the wire`() {
        send(WebhookConfig(url()))
        assertEquals(false, receivedBody.contains("203.0.113.7"), "ipAddress must not leak with the default PayloadConfig")

        send(WebhookConfig(url()), PayloadConfig(includeRoot = setOf("ipAddress")))
        assertEquals(true, receivedBody.contains("203.0.113.7"), "ipAddress must appear once opted into via PayloadConfig")
    }
}
