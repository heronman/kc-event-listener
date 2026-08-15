package net.agl.keycloak.feedback

import net.agl.keycloak.config.ConfigNode
import net.agl.keycloak.config.asInt
import net.agl.keycloak.config.asObjList
import net.agl.keycloak.config.asString
import net.agl.keycloak.config.get

/**
 * Config shape read from `feedback` in [net.agl.keycloak.config.AppConfig] (any number of entries
 * per type, including several of the same type):
 *
 * ```yaml
 * feedback:
 *   webhook:
 *     - url: https://hook1.example.com/kc-events
 *       api-key: secret-1              # optional, sent as the X-Api-Key header
 *   broker:
 *     kafka:
 *       - bootstrap-servers: localhost:9092
 *         topic: keycloak-events
 *         client-id: kc-event-listener # optional
 *         acks: all                    # optional
 *     mqtt:
 *       - broker-url: tcp://localhost:1883
 *         topic: keycloak/events
 *         client-id: kc-event-listener # optional, defaults to "kc-event-listener-<index>"
 *         username: mqttuser           # optional
 *         password: mqttpass           # optional
 *         qos: 1                       # optional, defaults to 1
 *     amqp:
 *       - host: localhost
 *         port: 5672                   # optional, defaults to 5672
 *         username: guest              # optional
 *         password: guest              # optional
 *         virtual-host: /              # optional, defaults to "/"
 *         exchange: keycloak.events
 *         routing-key: event
 * ```
 *
 * Every leaf here also picks up `KCEL_`-prefixed env var overrides and ./ , ./config file
 * overrides the same way the rest of AppConfig does — nothing feedback-specific about that part.
 */
data class WebhookConfig(val url: String, val apiKey: String?)

data class KafkaSinkConfig(
    val bootstrapServers: String,
    val topic: String,
    val clientId: String?,
    val acks: String?,
)

data class MqttSinkConfig(
    val brokerUrl: String,
    val topic: String,
    val clientId: String,
    val username: String?,
    val password: String?,
    val qos: Int,
)

data class AmqpSinkConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val virtualHost: String,
    val exchange: String,
    val routingKey: String,
)

internal fun parseWebhookConfigs(feedback: ConfigNode?): List<WebhookConfig> =
    feedback["webhook"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.webhook[$i]"
        WebhookConfig(
            url = cfg.requireString("url", context),
            apiKey = cfg["api-key"].asString(),
        )
    }

internal fun parseKafkaConfigs(feedback: ConfigNode?): List<KafkaSinkConfig> =
    feedback["broker"]["kafka"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.kafka[$i]"
        KafkaSinkConfig(
            bootstrapServers = cfg.requireString("bootstrap-servers", context),
            topic = cfg.requireString("topic", context),
            clientId = cfg["client-id"].asString(),
            acks = cfg["acks"].asString(),
        )
    }

internal fun parseMqttConfigs(feedback: ConfigNode?): List<MqttSinkConfig> =
    feedback["broker"]["mqtt"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.mqtt[$i]"
        MqttSinkConfig(
            brokerUrl = cfg.requireString("broker-url", context),
            topic = cfg.requireString("topic", context),
            clientId = cfg["client-id"].asString() ?: "kc-event-listener-$i",
            username = cfg["username"].asString(),
            password = cfg["password"].asString(),
            qos = cfg["qos"].asInt(1),
        )
    }

internal fun parseAmqpConfigs(feedback: ConfigNode?): List<AmqpSinkConfig> =
    feedback["broker"]["amqp"].asObjList().mapIndexed { i, cfg ->
        val context = "feedback.broker.amqp[$i]"
        AmqpSinkConfig(
            host = cfg.requireString("host", context),
            port = cfg["port"].asInt(5672),
            username = cfg["username"].asString(),
            password = cfg["password"].asString(),
            virtualHost = cfg["virtual-host"].asString() ?: "/",
            exchange = cfg.requireString("exchange", context),
            routingKey = cfg.requireString("routing-key", context),
        )
    }

private fun ConfigNode.Obj.requireString(key: String, context: String): String =
    this[key].asString() ?: throw IllegalArgumentException("$context: missing required '$key'")
