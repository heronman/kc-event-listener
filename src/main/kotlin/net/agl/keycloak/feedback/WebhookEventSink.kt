package net.agl.keycloak.feedback

import org.jboss.logging.Logger
import org.keycloak.events.Event
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * POSTs [FeedbackPayload] (shaped by [PayloadConfig] — see its kdoc) as JSON to [WebhookConfig.url].
 * Auth, if any, is layered on as headers in this order (later wins on a name clash):
 * [WebhookConfig.headers], [WebhookConfig.apiKey], [WebhookConfig.authorization],
 * [WebhookConfig.hmac] (a signature over the JSON body).
 */
class WebhookEventSink(private val config: WebhookConfig, private val payloadConfig: PayloadConfig) : EventSink {

    companion object {
        private val log = Logger.getLogger(WebhookEventSink::class.java)
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    override fun send(event: Event) {
        val body = feedbackPayloadJson(event, payloadConfig)
        val requestBuilder = HttpRequest.newBuilder(URI.create(config.url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headersFor(body).forEach { (name, value) -> requestBuilder.header(name, value) }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() >= 300) {
            log.warnf("Webhook %s responded with status %d for event type=%s", config.url, response.statusCode(), event.type)
        }
    }

    private fun headersFor(body: String): Map<String, String> {
        val headers = LinkedHashMap(config.headers)
        config.apiKey?.let { headers[it.header] = it.value }
        config.authorization?.let { headers["Authorization"] = authorizationHeaderValue(it) }
        config.hmac?.let { headers[it.header] = it.prefix + signature(it, body) }
        return headers
    }

    private fun authorizationHeaderValue(auth: AuthorizationConfig): String = when (auth) {
        is AuthorizationConfig.Basic -> {
            val credentials = "${auth.username}:${auth.password}".toByteArray(StandardCharsets.UTF_8)
            "Basic " + Base64.getEncoder().encodeToString(credentials)
        }
        is AuthorizationConfig.Token -> if (auth.type != null) "${auth.type} ${auth.token}" else auth.token
    }

    // A fresh Mac per call — javax.crypto.Mac isn't safe for concurrent doFinal(), and this sink
    // is shared across every request thread for the server's lifetime (see EventSinkRegistry).
    private fun signature(hmac: HmacConfig, body: String): String {
        val mac = Mac.getInstance(hmac.algorithm)
        mac.init(SecretKeySpec(hmac.secret.toByteArray(StandardCharsets.UTF_8), hmac.algorithm))
        return HexFormat.of().formatHex(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
    }

    override fun close() {
        client.close()
    }
}
