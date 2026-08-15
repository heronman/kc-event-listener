plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "net.agl.keycloak"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Provided by the Keycloak server at runtime — must NOT end up in the shadow jar.
    // keycloak-core is a "provided"-scope dependency of keycloak-server-spi, which Maven/Gradle
    // do not propagate transitively, so it must be declared explicitly here.
    compileOnly(libs.keycloak.core)
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.jboss.logging)

    // Bundled into the provider jar (see relocate() below) — YAML config parsing.
    implementation(libs.snakeyaml)

    // Feedback transports (net.agl.keycloak.feedback) — bundled, Keycloak doesn't provide these.
    implementation(libs.kafka.clients)
    implementation(libs.paho.mqtt)
    implementation(libs.rabbitmq.amqp.client)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Keycloak does not ship the Kotlin stdlib, so the provider jar deployed to
// $KEYCLOAK_HOME/providers/ must bundle it itself. shadowJar packages every
// non-compileOnly dependency (i.e. kotlin-stdlib) alongside the compiled classes.
tasks.shadowJar {
    archiveClassifier.set("")
    // Keycloak/Quarkus bundles its own snakeyaml on the server classpath; relocate ours
    // so the two never collide regardless of version skew.
    relocate("org.yaml.snakeyaml", "net.agl.keycloak.shaded.snakeyaml")
    // kafka-clients/paho/amqp-client each carry META-INF/services SPI files (compression codecs,
    // security providers, ...); merge instead of letting one dependency's file silently win.
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}