package kvas

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun Application.configureRouting() {
    val launcher by inject<Launcher>()
    val logStorage by inject<LogStorage>()

    routing {
        get("/-/healthy") {
                call.respond(mapOf("status" to "healthy"))
        }
        singlePageApplication {
            if (System.getProperty("io.ktor.development") == "true") {
//                react("frontend")
                react("frontend/dist")
            } else {
                react("frontend/dist")
            }
        }
        authenticate("default") {
            post("/api/test") {
                val image = call.parameters["image"]
                val nodeCount = call.parameters["node_count"]?.toIntOrNull() ?: 1
                if (image == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing image query parameter")
                    return@post
                }
                call.respond(launcher.launchTask(image, nodeCount))
            }
            get("/api/test/{taskId}") {
                val taskId: Uuid? = try {
                    call.parameters["taskId"]?.let { Uuid.parse(it) }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid task ID")
                    return@get
                }
                if (taskId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid task ID")
                    return@get
                }
                val task = launcher.getTask(taskId)
                if (task == null) {
                    call.respond(HttpStatusCode.NotFound, "Task not found")
                    return@get
                }
                call.respond(task)
            }
            get("/api/logs/{taskId}") {
                val taskId: Uuid? = try {
                    call.parameters["taskId"]?.let { Uuid.parse(it) }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid task ID")
                    return@get
                }
                if (taskId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid task ID")
                    return@get
                }

                call.respond(logStorage.getLogsURLs(taskId))
            }
        }
    }
}
