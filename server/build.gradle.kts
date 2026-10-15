plugins {
    application
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

application {
    mainClass.set("com.jadbarakat.blooddonor.server.MainKt")
}

// Load local DB settings from server/.env (git-ignored) when running with
// `./gradlew :server:run`. If the file is missing, real environment variables are used.
tasks.named<JavaExec>("run") {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // Ktor web server
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-netty:3.5.1")
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // PostgreSQL driver + connection pool
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
}