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
    private val ghcrToken: String = System.getenv("CR_PAT")!!

    override suspend fun launchTask(image: String, nodeCount: Int): LaunchResult {
        val uuid = Uuid.random()
        scope.launch {
            LOGGER.info("launched task $uuid with image $image and $nodeCount nodes")
            runLoadtest(uuid, image, nodeCount)
        }
        return LaunchResult(uuid)
    }

    suspend fun runLoadtest(uuid: Uuid, image: String, nodeCount: Int) {
        val clientVm = scope.async {
            val clientVm = vmRepository.getVm("kvclient", uuid)
            try {
                clientVm.runCommand("loginctl enable-linger", saveLogs = false)
                clientVm.runCommand("sudo apt install -y podman", saveLogs = false)
                clientVm.runCommand("podman login ghcr.io -u USERNAME -p $ghcrToken", saveLogs = false)
                clientVm.runCommand("podman pull ghcr.io/bdse-class-2024/kvclient:51bee0409", saveLogs = false)
            } catch (e: Exception) {
                clientVm.close()
                throw e
            }
            return@async clientVm
        }
        (1..nodeCount).map { _ -> scope.async { vmRepository.getVm("kvnode", uuid) } }.awaitAll().useAll { vms ->
            LOGGER.info("launched all nodes for task $uuid")
            vms.map { vm ->
                scope.async {
                    vm.runCommand("loginctl enable-linger", saveLogs = false)
                    vm.runCommand("sudo apt install -y podman", saveLogs = false)
                    vm.runCommand("podman login ghcr.io -u USERNAME -p $ghcrToken", saveLogs = false)
                    vm.runCommand("podman network create kvas-network", saveLogs = false)
//                    vm.runCommand("podman run -d --name kvas-postgres --network kvas-network -e POSTGRES_HOST_AUTH_METHOD=trust docker.io/library/postgres:16-bookworm", saveLogs = false)
                    vm.runCommand("podman pull ghcr.io/bdse-class-2024/kvnode:51bee0409", saveLogs = false)
                }
            }.awaitAll()
            LOGGER.info("Setup of all nodes done for task $uuid")
            val leader = vms.first()
            leader.runCommand("podman run -d --name kvas-node -p 9000:9000 ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=${leader.ip}:9000 --storage=memory replication --role=leader metadata --master", saveLogs = false)
            vms.drop(1).map { vm ->
                scope.async {
                    vm.runCommand("podman run -d --name kvas-node -p 9000:9000 ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=${vm.ip}:9000 --storage=memory metadata --address ${leader.ip}:9000 replication --role=follower", saveLogs = false)
                }
            }.awaitAll()
            LOGGER.info("All nodes launched kvas for task $uuid")
            clientVm.await().use { clientVm ->
                clientVm.runCommand("podman run --name kvas-client ghcr.io/bdse-class-2024/kvclient:51bee0409 --metadata-address=${leader.ip}:9000 loadtest --key-count=10", saveLogs=true)
            }
            LOGGER.info("Loadtest finished for task $uuid")
            vms.map { vm ->
                scope.async {
                    vm.runCommand("podman logs kvas-node", saveLogs=true)
                }
            }.awaitAll()
            LOGGER.info("Logs saved for task $uuid")
        }
        LOGGER.info("executed all commands for task $uuid")
    }

    override fun close() {
        vmRepository.close()
    }
}