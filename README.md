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

Produces several self-contained (shaded) jars under `build/libs/`, all bundling Kotlin stdlib and
SnakeYAML (relocated to avoid clashing with the copy Keycloak/Quarkus ships) plus, per variant,
that transport's client library — most deployments use one transport, never all three brokers at
once, so there's no reason to ship `kafka-clients` (~20MB of transitive deps) to a webhook-only
deployment:

| Jar | Classifier | Bundles |
| --- | --- | --- |
| `kc-event-listener-<version>.jar` | *(none)* | webhook + Kafka + MQTT + AMQP — everything |
| `kc-event-listener-<version>-webhook.jar` | `webhook` | webhook only |
| `kc-event-listener-<version>-kafka.jar` | `kafka` | webhook + `kafka-clients` |
| `kc-event-listener-<version>-mqtt.jar` | `mqtt` | webhook + Eclipse Paho |
| `kc-event-listener-<version>-amqp.jar` | `amqp` | webhook + `amqp-client` (RabbitMQ) |

All five are published to the Maven repo too (see `publishing` in `build.gradle.kts`), so a
deployment can pull the one it needs by classifier instead of building from source.

## Deploy

Pick the jar matching the transport(s) you're using (see the table above) and drop just that one in:

```bash
cp build/libs/kc-event-listener-<version>-webhook.jar $KEYCLOAK_HOME/providers/
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
  payload:                           # optional; shapes what every sink actually sends — see below
    include-root: [realmId]          # optional, default []; subset of id, realmId, ipAddress
    include-details: [redirect_uri]  # optional, default []; Event.details keys to forward

  webhook:
    - url: https://hook1.example.com/kc-events
      api-key:                       # optional
        value: secret-1
        header: X-Api-Key            # optional, defaults to X-Api-Key
      authorization:                 # optional, sent as the Authorization header
        token: secret-2
        type: Bearer                 # optional, defaults to Bearer; "none" -> raw "Authorization: <token>"
        # or, for Basic auth instead of a token:
        # username: user
        # password: pass
      hmac:                          # optional, signs the request body
        secret: shared-secret
        header: X-Signature-256      # optional, defaults to X-Signature-256
        algorithm: HmacSHA256        # optional, defaults to HmacSHA256
        prefix: "sha256="            # optional, defaults to "" (GitHub-style webhooks use "sha256=")
      headers:                       # optional, sent as-is with every request; catch-all for anything else
        X-Custom-Header: value

  broker:
    kafka:
      - bootstrap-servers: localhost:9092
        topic: keycloak-events
        client-id: kc-event-listener # optional
        acks: all                    # optional
        tls:                        # optional — TLS is on by default for every broker, see below
          enabled: true               # optional, defaults to true
          trusted-certificates:       # optional, PEM file paths; pins trust to these instead of the JVM default
            - /etc/keycloak/certs/broker-ca.pem

    mqtt:
      - broker-url: ssl://localhost:8883  # ssl:// (or wss://) is what actually enables TLS on the wire
        topic: keycloak/events
        client-id: kc-event-listener # optional, defaults to "kc-event-listener-<index>"
        username: mqttuser           # optional
        password: mqttpass           # optional
        qos: 1                       # optional, defaults to 1
        tls:
          trusted-certificates:
            - /etc/keycloak/certs/broker-ca.pem

    amqp:
      - host: localhost
        port: 5671                   # optional, defaults to 5671 with tls enabled (the default), else 5672
        username: guest              # optional
        password: guest              # optional
        virtual-host: /              # optional, defaults to "/"
        exchange: keycloak.events
        routing-key: event
        tls:
          trusted-certificates:
            - /etc/keycloak/certs/broker-ca.pem
```

Equivalent `.properties` form (dotted/indexed keys, same tree shape):

```properties
feedback.webhook[0].url=https://hook1.example.com/kc-events
feedback.webhook[0].api-key.value=secret-1
feedback.broker.kafka[0].bootstrap-servers=localhost:9092
feedback.broker.kafka[0].topic=keycloak-events
```

Or override a single leaf via environment variable, on top of whatever the files say:

```bash
export KCEL_FEEDBACK_WEBHOOK_0_API_KEY_VALUE=prod-secret
```

#### What's actually sent

Every sink receives the **same** shaped payload — not the raw `org.keycloak.events.Event` — built
per `feedback.payload` and serialized as JSON via `org.keycloak.util.JsonSerialization` (Keycloak's
own bundled Jackson, nothing extra pulled in for this). `Event` carries PII (username, email,
IP address, ...) in its root fields and its open-ended `details` map, and not every consumer needs
it — a session-revocation listener, for instance, only needs `type`/`userId`/`sessionId`. So by
default only the low-cardinality, non-PII fields go out: `time`, `type`, `realmName`, `clientId`,
`userId`, `sessionId`, `error`, plus a `details` object that's **empty unless configured**.
`feedback.payload.include-root` (a subset of `id`, `realmId`, `ipAddress` — everything else on
`Event`'s root is already always-sent) and `include-details` (any key from `Event`'s own `details`
map, e.g. `redirect_uri`, `username`) opt specific fields back in; both land in the outgoing
`details` object under their original name. See `net.agl.keycloak.feedback.FeedbackPayload`'s kdoc.

One HTTP POST / message per transport per event:

- **Webhook**: `POST` to `url`, `Content-Type: application/json`, plus (in this order, later wins
  on a header-name clash) any `headers` given, `api-key`, `authorization`, then `hmac` — a
  signature computed over the JSON body, so it always reflects what's actually sent.
- **Kafka**: value = payload JSON, key = `userId`, sent to `topic`.
- **MQTT**: payload = payload JSON, published to `topic` at the given `qos`.
- **AMQP**: payload = payload JSON, published to `exchange` with `routing-key`.

#### Broker TLS

Kafka, MQTT, and AMQP each take a `tls` block (see `net.agl.keycloak.feedback.TlsConfig`), **on by
default**:

- Kafka/AMQP: `tls.enabled` (default `true`) directly selects `SSL`/`useSslProtocol()` on the
  client — set it to `false` for a deliberately-unencrypted broker (this also silences the sink's
  plaintext warning). Since AMQP's default port depends on this, `port` defaults to `5671` when
  TLS is enabled and `5672` when it isn't, unless set explicitly.
- MQTT is different: the wire transport is fixed by the `ssl://`/`wss://` scheme in `broker-url`
  itself, so `tls.enabled` can't override that — it only gates the plaintext warning.
- `tls.trusted-certificates` (all three): PEM file paths. When set, the connection trusts **only**
  these certs instead of the JVM's default trust store — for a broker on a closed network behind a
  self-signed or locally-issued cert. For MQTT this applies whenever `broker-url` is already
  `ssl://`/`wss://`, regardless of `tls.enabled`.

A sink that ends up connecting without TLS — `tls.enabled: false` on Kafka/AMQP, or a plaintext
`broker-url` on MQTT — logs a warning on startup so a plaintext broker doesn't go unnoticed.

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
