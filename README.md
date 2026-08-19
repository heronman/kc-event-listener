# kc-event-listener

Keycloak 26.7 Event Listener SPI provider. On every user-facing event (`org.keycloak.events.Event`
— login, logout, register, ...) it forwards the event to zero or more configured feedback
transports: HTTP webhooks, Kafka, MQTT, and/or AMQP (RabbitMQ). Layered, Spring-Boot-style
configuration (YAML/properties files + environment variables).

## Requirements

- JDK 21 (Gradle toolchain, resolved automatically via the `foojay-resolver-convention` plugin)
- Target server: Keycloak **26.7.x** (`keycloak` version in `gradle/libs.versions.toml` must match)

## Build

```bash
./gradlew build
```

Produces a single self-contained (shaded) jar at `build/libs/kc-event-listener-<version>.jar`.
It bundles everything Keycloak doesn't already provide — Kotlin stdlib, SnakeYAML (relocated to
avoid clashing with the copy Keycloak/Quarkus ships), `kafka-clients`, Eclipse Paho (MQTT), and
`amqp-client` (RabbitMQ). Expect the jar to be tens of MB, mostly `kafka-clients`.

## Deploy

```bash
cp build/libs/kc-event-listener-<version>.jar $KEYCLOAK_HOME/providers/
$KEYCLOAK_HOME/bin/kc.sh build
```

Enable the provider in `keycloak.conf` (or via `KC_SPI_EVENTS_LISTENER_USER_EVENT_LISTENER_ENABLED=true`):

```properties
spi-events-listener-user-event-listener-enabled=true
```

Then, per realm: **Realm settings → Events → Event listeners config** (or via the admin REST API)
→ add `user-event-listener` to the list. Only realms with it added will forward events to the
configured feedback transports.

## Configuration

Configuration is layered, Spring-Boot style, and lives entirely outside of Keycloak's own
`spi-events-listener-*` config mechanism (that one is still available for anything you add to
`UserEventListenerProviderFactory.init(Config.Scope)`, but isn't used by the pieces described
here). See `net.agl.keycloak.config.AppConfig`'s kdoc for the authoritative source of truth; the
gist:

**Sources, lowest to highest priority** (a later source overrides matching keys from an earlier
one; nested objects are merged recursively, arrays are replaced wholesale, not merged element by
element):

1. `classpath:/kc-event-listener.{yml,yaml,properties}` — bundled inside the provider jar
   (`src/main/resources/kc-event-listener.yml` in this repo, shipped fully commented out as a
   reference — nothing in it is active by default)
2. `file:./kc-event-listener.{yml,yaml,properties}` — relative to `$KEYCLOAK_HOME` (or wherever
   the server process's working directory is)
3. `file:./config/kc-event-listener.{yml,yaml,properties}`
4. OS environment variables, prefixed **`KCEL_`** — dots/dashes in the key collapse to `_` and the
   whole name is upper-cased (relaxed binding), e.g. `feedback.webhook[0].url` →
   `KCEL_FEEDBACK_WEBHOOK_0_URL`. Env vars are flat and only override individual leaf values, they
   can't inject a whole subtree.

Within a single location, if both a YAML file and a `.properties` file are present, the
`.properties` file wins for keys they both define.

The config is exposed as a **tree**, not a flat map — `ConfigNode` in
`net.agl.keycloak.config` — so nested sections can be navigated directly:

```kotlin
AppConfig.tree["feedback"]["webhook"]              // ConfigNode.Arr or null
AppConfig.get("feedback.webhook[0].url")           // String?, with KCEL_ env override applied
```

### Feedback transports

Read from the `feedback` section. Any number of entries per transport type is allowed, including
several of the same type (e.g. two webhooks, or a Kafka producer pointed at two different
clusters). A transport that fails to start (missing required field, unreachable broker) is logged
and skipped — it does not prevent the others from starting. See
`net.agl.keycloak.feedback.WebhookConfig`'s kdoc for the parsed data classes.

```yaml
feedback:
  webhook:
    - url: https://hook1.example.com/kc-events
      headers:                       # optional, sent as-is with every request (e.g. auth headers)
        X-Api-Key: secret-1

  broker:
    kafka:
      - bootstrap-servers: localhost:9092
        topic: keycloak-events
        client-id: kc-event-listener # optional
        acks: all                    # optional

    mqtt:
      - broker-url: tcp://localhost:1883
        topic: keycloak/events
        client-id: kc-event-listener # optional, defaults to "kc-event-listener-<index>"
        username: mqttuser           # optional
        password: mqttpass           # optional
        qos: 1                       # optional, defaults to 1

    amqp:
      - host: localhost
        port: 5672                   # optional, defaults to 5672
        username: guest              # optional
        password: guest              # optional
        virtual-host: /              # optional, defaults to "/"
        exchange: keycloak.events
        routing-key: event
```

Equivalent `.properties` form (dotted/indexed keys, same tree shape):

```properties
feedback.webhook[0].url=https://hook1.example.com/kc-events
feedback.webhook[0].headers.X-Api-Key=secret-1
feedback.broker.kafka[0].bootstrap-servers=localhost:9092
feedback.broker.kafka[0].topic=keycloak-events
```

Or override a single leaf via environment variable, on top of whatever the files say:

```bash
export KCEL_FEEDBACK_WEBHOOK_0_HEADERS_X_API_KEY=prod-secret
```

Every sink receives the **same** event, serialized as JSON via
`org.keycloak.util.JsonSerialization` (Keycloak's own bundled Jackson — nothing extra pulled in
for this), one HTTP POST / message per transport per event:

- **Webhook**: `POST` to `url`, `Content-Type: application/json`, plus any `headers` given (e.g.
  for auth — the schema doesn't assume a particular scheme). Body is the full `Event` (type, time,
  realmId, clientId, userId, sessionId, ipAddress, error, details).
- **Kafka**: value = event JSON, key = `userId`, sent to `topic`.
- **MQTT**: payload = event JSON, published to `topic` at the given `qos`.
- **AMQP**: payload = event JSON, published to `exchange` with `routing-key`.

### Known limitations

- `EventSinkRegistry.sendAll` runs **synchronously** on the request thread that fired the event
  (e.g. the login request) — a slow webhook or broker will add latency to that request. Fine for
  a low-volume/internal setup; for anything higher-traffic, dispatch through an executor instead.
- Kafka's producer connects lazily — a bad `bootstrap-servers` value won't fail at server startup,
  only on the first `send()` (logged, not thrown).
- Only `org.keycloak.events.Event` is forwarded to feedback transports; admin events
  (`AdminEvent`) are not.

## Project layout

```
net.agl.keycloak.listener    UserEventListenerProvider(Factory) — the Keycloak SPI entry point
net.agl.keycloak.config    AppConfig / ConfigNode — layered config tree loader
net.agl.keycloak.feedback  EventSink + WebhookEventSink/KafkaEventSink/MqttEventSink/AmqpEventSink
                            + EventSinkRegistry (builds sinks from config) + FeedbackConfig (parsing)
```

## Test

```bash
./gradlew test
```

Covers config loading/merging (`AppConfigTest`) and feedback config parsing
(`FeedbackConfigTest`) — pure logic, no network. The transports themselves (webhook/Kafka/MQTT/AMQP
clients) need live infrastructure and aren't exercised by the test suite.
