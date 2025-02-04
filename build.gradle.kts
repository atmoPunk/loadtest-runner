
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "kvas"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
}

jib {
    from {
        image = "docker://base-ssh-keys"
    }
    extraDirectories {
        paths {
            path {
                setFrom("frontend/dist")
                into = "${jib.container.appRoot}/frontend/dist"
            }
        }
    }
    to {
        image = "us-east1-docker.pkg.dev/kvas-loadtester/kvas-images/loadtest-runner:latest"
    }

}

dependencies {
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.rate.limiting)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(platform("com.google.cloud:libraries-bom:26.53.0"))
    implementation("com.google.cloud:google-cloud-compute")
    implementation("com.google.cloud:google-cloud-storage")
    implementation(platform("io.insert-koin:koin-bom:4.0.2"))
    implementation("io.insert-koin:koin-core")
    implementation("io.insert-koin:koin-ktor")
    implementation("io.insert-koin:koin-logger-slf4j")
    implementation("com.hierynomus:sshj:0.39.0")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

task<Exec>("vite-build") {
    workingDir("frontend")
    commandLine("npm", "run", "build")
}

