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