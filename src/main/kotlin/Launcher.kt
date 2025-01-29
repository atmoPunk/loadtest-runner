@file:OptIn(ExperimentalUuidApi::class)

package kvas

import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import java.io.Closeable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class LaunchResult(val uuid: Uuid)

interface Launcher : Closeable {
    suspend fun launchTask(image: String, nodeCount: Int): LaunchResult
}

class LauncherImpl(val vmRepository: VMRepository): Launcher, KoinComponent {
    companion object {
        val LOGGER = KtorSimpleLogger("kvas.LauncherImpl")
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override suspend fun launchTask(image: String, nodeCount: Int): LaunchResult {
        val uuid = Uuid.random()
        scope.launch {
            LOGGER.info("launched task $uuid with image $image and $nodeCount nodes")
            runLoadtest(uuid, image, nodeCount)
        }
        return LaunchResult(uuid)
    }

    suspend fun runLoadtest(uuid: Uuid, image: String, nodeCount: Int) {
        (1..nodeCount).map { _ -> scope.async { vmRepository.getVm() } }.awaitAll().useAll { vms ->
            LOGGER.info("launched all nodes for task $uuid")
            vms.map { vm ->
                scope.async {
                    vm.runCommand("echo 'hetto' | wc")
                    vm.runCommand("gcloud --help")
                }
            }.awaitAll()
            LOGGER.info("executed all commands for task $uuid")
        }
    }

    override fun close() {
        vmRepository.close()
    }
}