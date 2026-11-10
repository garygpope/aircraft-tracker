plugins {
    id("io.ktor.plugin") version "2.3.5"
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
}

group = "com.codingpitch"
version = "0.0.1-SNAPSHOT"

repositories { mavenCentral() }

val ktorVersion = "2.3.5"
dependencies {
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.4.11")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("com.codingpitch.aircrafttracker.ApplicationKt") }
tasks.test { useJUnitPlatform() }
