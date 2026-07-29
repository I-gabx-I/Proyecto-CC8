plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.6.10"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Jetpack Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.runtime)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}