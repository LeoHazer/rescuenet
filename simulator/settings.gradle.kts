// Build independente do app Android: o simulador roda na JVM do computador.
// Execute a partir da raiz do repositório com o wrapper do projeto:
//   .\gradlew.bat -p simulator run
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rescuenet-simulator"
