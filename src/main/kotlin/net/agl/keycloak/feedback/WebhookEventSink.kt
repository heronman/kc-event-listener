package net.agl.keycloak.feedback

import org.jboss.logging.Logger
import org.keycloak.events.Event
import org.keycloak.util.JsonSerialization
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** POSTs the full event as JSON to [WebhookConfig.url]; sends [WebhookConfig.headers] as-is (auth scheme is up to the caller). */
class WebhookEventSink(private val config: WebhookConfig) : EventSink {

    companion object {
        private val log = Logger.getLogger(WebhookEventSink::class.java)
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    override fun send(event: Event) {
        val body = JsonSerialization.writeValueAsString(event)
        val requestBuilder = HttpRequest.newBuilder(URI.create(config.url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        config.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() >= 300) {
            log.warnf("Webhook %s responded with status %d for event type=%s", config.url, response.statusCode(), event.type)
        }
    }

    override fun close() {
        client.close()
    }
}
