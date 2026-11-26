import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Mesma versão de Kotlin do app (gradle/libs.versions.toml).
    kotlin("jvm") version "2.2.10"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        kotlin {
            // Os nós simulados executam exatamente o código de eleição do app:
            // os arquivos são compilados a partir da pasta do app, sem cópia.
            srcDir("../app/src/main/java/br/edu/uff/rescuenet/data/election")
        }
    }
}

dependencies {
    // Mesmo cliente MQTT usado no app.
    implementation("com.hivemq:hivemq-mqtt-client:1.4.1")
    // No Android, org.json faz parte da plataforma; na JVM vem desta biblioteca.
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("br.edu.uff.rescuenet.simulator.MainKt")
}

tasks.named<JavaExec>("run") {
    // Encaminha Ctrl+C para o encerramento limpo dos nós (LWT publicado).
    standardInput = System.`in`
}
