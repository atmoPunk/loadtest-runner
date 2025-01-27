package kvas

import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.common.IOUtils

fun main(args: Array<String>) {
//    val client = SSHClient()
//    client.addHostKeyVerifier(PromiscuousVerifier())
//    client.connect("10.142.0.18")
//    client.authPublickey("atmopunk", "/home/neuromancer/.ssh/id_ed25519")
//    client.startSession().use { session ->
//        val cmd = session.exec("ping -c 1 google.com")
//        println(IOUtils.readFully(cmd.inputStream))
//        cmd.join()
//    }
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
