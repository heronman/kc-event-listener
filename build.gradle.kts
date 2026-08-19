import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    id("maven-publish")
}

group = "net.agl.keycloak"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Most deployments use one feedback transport (or none — webhook only), never all three brokers,
// so each gets its own resolvable configuration here, consumed by exactly one shadowJar variant
// below (ShadowJar needs real Configurations to resolve, not derived FileCollections, hence
// declaring dependencies again per-configuration rather than deriving these from `implementation`).
// `variantRuntime` is the shared base (YAML parsing + Kotlin stdlib) every variant bundles.
val variantRuntime by configurations.creating { isCanBeConsumed = false }
val kafkaOnly by configurations.creating { isCanBeConsumed = false; extendsFrom(variantRuntime) }
val mqttOnly by configurations.creating { isCanBeConsumed = false; extendsFrom(variantRuntime) }
val amqpOnly by configurations.creating { isCanBeConsumed = false; extendsFrom(variantRuntime) }

dependencies {
    // Provided by the Keycloak server at runtime — must NOT end up in the shadow jar.
    // keycloak-core is a "provided"-scope dependency of keycloak-server-spi, which Maven/Gradle
    // do not propagate transitively, so it must be declared explicitly here.
    compileOnly(libs.keycloak.core)
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.jboss.logging)

    // Bundled into every provider jar variant — YAML config parsing needed regardless of transport.
    implementation(libs.snakeyaml)
    variantRuntime(libs.snakeyaml)
    variantRuntime(kotlin("stdlib"))

    // Feedback transports (net.agl.keycloak.feedback) — the whole module compiles/tests against
    // all of them, but each shadowJar variant below bundles only its own.
    implementation(libs.kafka.clients)
    implementation(libs.paho.mqtt)
    implementation(libs.rabbitmq.amqp.client)
    kafkaOnly(libs.kafka.clients)
    mqttOnly(libs.paho.mqtt)
    amqpOnly(libs.rabbitmq.amqp.client)

    testImplementation(kotlin("test"))
    // compileOnly deps aren't visible to the test source set by default; WebhookEventSinkTest
    // exercises the sink directly, so it needs the same Keycloak classes main compiles against.
    testImplementation(libs.keycloak.core)
    testImplementation(libs.keycloak.server.spi)
    testImplementation(libs.keycloak.server.spi.private)
    testImplementation(libs.jboss.logging)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Keycloak does not ship the Kotlin stdlib, so every provider jar deployed to
// $KEYCLOAK_HOME/providers/ must bundle it itself — shadowJar packages whatever configuration(s)
// it's given alongside the compiled classes. There's one set of compiled classes (all sink types;
// a sink you never configure just never gets loaded, see EventSinkRegistry) but several jar
// variants below, one per third-party client library bundled.
fun ShadowJar.configureCommon() {
    from(sourceSets.main.get().output)
    // Keycloak/Quarkus bundles its own snakeyaml on the server classpath; relocate ours
    // so the two never collide regardless of version skew.
    relocate("org.yaml.snakeyaml", "net.agl.keycloak.shaded.snakeyaml")
    // kafka-clients/paho/amqp-client each carry META-INF/services SPI files (compression codecs,
    // security providers, ...); merge instead of letting one dependency's file silently win.
    mergeServiceFiles()
}

tasks.shadowJar {
    // The "everything bundled" variant — every transport in one jar, published unclassified
    // (unchanged from before per-transport variants existed, so existing consumers don't break).
    archiveClassifier.set("")
    configureCommon()
}

val shadowJarWebhook by tasks.registering(ShadowJar::class) {
    group = "shadow"
    description = "Assembles a provider jar with the webhook transport only (no broker client library)."
    archiveClassifier.set("webhook")
    configurations = listOf(variantRuntime)
    configureCommon()
}

val shadowJarKafka by tasks.registering(ShadowJar::class) {
    group = "shadow"
    description = "Assembles a provider jar with the Kafka transport (and webhook) only."
    archiveClassifier.set("kafka")
    configurations = listOf(kafkaOnly)
    configureCommon()
}

val shadowJarMqtt by tasks.registering(ShadowJar::class) {
    group = "shadow"
    description = "Assembles a provider jar with the MQTT transport (and webhook) only."
    archiveClassifier.set("mqtt")
    configurations = listOf(mqttOnly)
    configureCommon()
}

val shadowJarAmqp by tasks.registering(ShadowJar::class) {
    group = "shadow"
    description = "Assembles a provider jar with the AMQP transport (and webhook) only."
    archiveClassifier.set("amqp")
    configurations = listOf(amqpOnly)
    configureCommon()
}

val sinkShadowJars = listOf(shadowJarWebhook, shadowJarKafka, shadowJarMqtt, shadowJarAmqp)

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn(sinkShadowJars)
}

java {
    withSourcesJar()
    withJavadocJar()
    withJavadocJar()
}

publishing {
    val publishVersion = project.version.toString()

    publications {
        create<MavenPublication>("maven") {
            version = publishVersion
            groupId = project.group.toString()
            artifactId = project.name
            from(components["java"])
            // The unclassified artifact above is the "everything bundled" jar (see shadowJar's
            // wiring into the java component); these add the per-transport variants alongside it.
            sinkShadowJars.forEach { artifact(it) }
        }
    }

    repositories {
        maven {
            name = "maven"
            url = uri(
                findProperty(
                    if (publishVersion.endsWith("-SNAPSHOT"))
                        "repo.publish.snapshots"
                    else
                        "repo.publish.releases"
                )!! as String
            )
            credentials {
                username = findProperty("repo.publish.username")!! as String
                password = findProperty("repo.publish.password")!! as String
            }
        }
    }
}
