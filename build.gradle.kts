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

tasks.register<JavaExec>("runHistoricalBacktest") {
    group = "application"
    description = "Runs the 1-month historical backtest simulator using real market data"
    mainClass.set("com.mobatrade.core.engine.HistoricalBacktestRunner")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runSignalDiagnostics") {
    group = "application"
    description = "Runs the quantitative signal diagnostics runner to analyze entry edge"
    mainClass.set("com.mobatrade.core.engine.SignalDiagnosticsRunner")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runTrendTemplateDiagnostics") {
    group = "application"
    description = "Runs the Trend Template signal diagnostics script to analyze MA conditions and pullback triggers"
    mainClass.set("com.mobatrade.core.engine.TrendTemplateDiagnostics")
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

tasks.register<JavaExec>("forceZoyaSync") { mainClass.set("com.mobatrade.core.engine.ForceZoyaSyncKt"); classpath = sourceSets["main"].runtimeClasspath }
tasks.register<JavaExec>("runVolatilityScreener") { mainClass.set("com.mobatrade.core.engine.VolatilityScreener"); classpath = sourceSets["main"].runtimeClasspath }
tasks.register<JavaExec>("runTrendTemplateShadowScanner") {
    group = "application"
    description = "Runs the Trend Template Shadow Scanner to detect new Version F entries and update active paper trades"
    mainClass.set("com.mobatrade.core.engine.TrendTemplateShadowScanner")
    classpath = sourceSets["main"].runtimeClasspath
}
