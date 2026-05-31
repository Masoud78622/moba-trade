plugins {
    kotlin("jvm") version "2.0.0"
    application
}

group = "com.mobatrade"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("commons-codec:commons-codec:1.16.1") // useful for base32 decodes (for TOTP)
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21) // Targets Java 21, matching host's Java 25 compatibility
}

tasks.register<JavaExec>("runBacktest") {
    group = "application"
    description = "Runs the historical backtest simulator CLI"
    mainClass.set("com.mobatrade.core.engine.BacktestRunner")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Runs the MobaTrade local HTTP API server"
    mainClass.set("com.mobatrade.core.engine.MobaTradeServer")
    classpath = sourceSets["main"].runtimeClasspath
}

application {
    mainClass.set("com.mobatrade.core.engine.MobaTradeServer")
}

