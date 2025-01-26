package kvas

import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(Koin) {
        modules(applicationModule)
    }
    configureSecurity()
    configureAdministration()
    configureSerialization()
    configureRouting()
}
