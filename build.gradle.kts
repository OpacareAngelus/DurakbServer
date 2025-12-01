plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
    id("com.gradleup.shadow") version "9.2.2"
    id("application")
}

group = "com.igeyming.durakserver"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.corundumstudio.socketio:netty-socketio:2.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
}

kotlin {
    jvmToolchain(18)
}

application {
    mainClass.set("server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks {
    shadowJar {
        archiveBaseName.set("durakb")
        archiveVersion.set("")
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
        manifest {
            attributes(
                "Main-Class" to "server.MainKt"
            )
        }
    }
}