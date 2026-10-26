plugins {
    application
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("com.jadbarakat.blooddonor.server.MainKt")
}

// Load local settings (DB, Firebase) from server/.env (git-ignored) when running with
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
// Flyway needs Java 17+; the server runs on Java 21 (the :app module is unaffected).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    // Ktor web server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.body.limit)
    // Logging
    implementation(libs.logback.classic)
    // PostgreSQL driver + connection pool
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    // Database migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    // SQL queries
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    // Firebase ID-token verification. We only use Auth, so drop the heavy
    // Firestore and Cloud Storage clients to keep memory use low.
    implementation(libs.firebase.admin) {
        exclude(group = "com.google.cloud", module = "google-cloud-firestore")
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
    }
    // Firebase Admin needs JacksonFactory, which used to arrive via the excluded modules.
    implementation(libs.google.http.client.jackson2)
    // Phone number validation
    implementation(libs.libphonenumber)
    // Security floors: one Netty version for every module (Ktor and Firebase/gRPC pull different
    // ones), and a jackson-core without the known number/document length bypasses.
    implementation(platform(libs.netty.bom))
    constraints {
        implementation(libs.jackson.core) { because("GHSA-72hv-8253-57qq and follow-ups, fixed in 2.18.8+") }
    }

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
