plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
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