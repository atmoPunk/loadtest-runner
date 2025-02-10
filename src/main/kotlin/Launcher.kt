@file:OptIn(ExperimentalUuidApi::class, ExperimentalUuidApi::class)

package kvas

import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class TaskState {
    SETUP,
    RUNNING,
    FINISHED,
    FAILURE,
}

@Serializable
sealed interface TaskType {
    fun getLeaderCommand(leaderIp: String): String
    fun getVmCommand(leaderIp: String, vmIp: String): String

    companion object {
        fun fromString(input: String): TaskType {
            return when (input) {
                "replication" -> ReplicationTaskType
                "sharding-naive" -> NaiveShardingTaskType
                else -> throw IllegalArgumentException("Invalid task type: $input")
            }
        }
    }
}

@Serializable
data object ReplicationTaskType : TaskType {
    override fun getLeaderCommand(leaderIp: String): String {
        return "sudo docker run -d --name kvas-node -p 9000:9000 --network kvas-network ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=$leaderIp:9000 --storage=dbms --db-host kvas-postgres replication --role=leader metadata --master"
    }

    override fun getVmCommand(leaderIp: String, vmIp: String): String {
        return "sudo docker run -d --name kvas-node -p 9000:9000 --network kvas-network ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=$vmIp:9000 --storage=dbms --db-host kvas-postgres metadata --address $leaderIp:9000 replication --role=follower"
    }
}

@Serializable
data object NaiveShardingTaskType : TaskType {
    override fun getLeaderCommand(leaderIp: String): String {
        return "sudo docker run -d --name kvas-node -p 9000:9000 --network kvas-network ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=$leaderIp:9000 --storage=dbms --db-host kvas-postgres --sharding naive metadata --master"
    }

    override fun getVmCommand(leaderIp: String, vmIp: String): String {
        return "sudo docker run -d --name kvas-node -p 9000:9000 --network kvas-network ghcr.io/bdse-class-2024/kvnode:51bee0409 --self-address=$vmIp:9000 --storage=dbms --db-host kvas-postgres --sharding naive metadata --address $leaderIp:9000"
    }
}


@Serializable
data class Task(val uuid: Uuid, var state: TaskState, var client: VM?, var nodes: MutableList<VM>, var leaderInstance: String? = null)

interface Launcher : Closeable {
    suspend fun launchTask(image: String, nodeCount: Int, taskType: TaskType): Task
    fun getTask(uuid: Uuid): Task?
}

class LauncherImpl(private val vmRepository: VMRepository): Launcher, KoinComponent {
    companion object {
        val LOGGER = KtorSimpleLogger("kvas.LauncherImpl")
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val ghcrToken: String = System.getenv("CR_PAT")!!
    private val tasks = ConcurrentHashMap<Uuid, Task>()

    override suspend fun launchTask(image: String, nodeCount: Int, taskType: TaskType): Task {
        val uuid = Uuid.random()
        tasks[uuid] = Task(uuid, TaskState.SETUP, null, mutableListOf())
        scope.launch {
            runLoadtest(uuid, image, nodeCount, taskType)
        }
        LOGGER.info("launched task $uuid with image $image and $nodeCount nodes")
        return tasks[uuid]!!
    }

    private suspend fun runLoadtest(uuid: Uuid, image: String, nodeCount: Int, taskType: TaskType) {
        try {
            val clientVm = scope.async {
                val clientVm = vmRepository.getVm("kvclient", uuid)
                tasks[uuid]!!.client = clientVm
                try {
                    // TODO: prepare image
                    // clientVm.runCommand("loginctl enable-linger", saveLogs = false)
                    // clientVm.runCommand("sudo apt install -y podman", saveLogs = false)
                    clientVm.runCommand("sudo docker login ghcr.io -u USERNAME -p $ghcrToken", saveLogs = false)  // TODO: Set token in VM env at creation
                    clientVm.runCommand("sudo docker pull $image", saveLogs = false)  // TODO: sanitize
                } catch (e: Exception) {
                    clientVm.close()
                    throw e
                }
                return@async clientVm
            }
            (1..nodeCount).map { _ -> scope.async { vmRepository.getVm("kvnode", uuid) } }.awaitAll().useAll { vms ->
                tasks[uuid]!!.nodes.addAll(vms)
                LOGGER.info("launched all nodes for task $uuid")
                vms.map { vm ->
                    scope.async {
                        vm.runCommand("sudo docker login ghcr.io -u USERNAME -p $ghcrToken", saveLogs = false)
                        vm.runCommand(
                            "sudo docker run -d --name kvas-postgres --network kvas-network -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-bookworm",
                            saveLogs = false
                        )
                        vm.runCommand("sudo docker pull ghcr.io/bdse-class-2024/kvnode:51bee0409", saveLogs = false)
                        vm.runCommand(
                            "sudo docker run --rm --entrypoint cat ghcr.io/bdse-class-2024/kvnode:51bee0409 /app/resources/postgres-init.sql > ~/postgres-init.sql",
                            saveLogs = false
                        )
                        vm.runCommand(
                            "sudo docker cp ~/postgres-init.sql kvas-postgres:/root/postgres-init.sql",
                            saveLogs = false
                        )
                        vm.runCommand(
                            "sudo docker exec kvas-postgres psql -h localhost -U postgres -f /root/postgres-init.sql",
                            saveLogs = false
                        )
                    }
                }.awaitAll()
                LOGGER.info("Setup of all nodes done for task $uuid")
                tasks[uuid]!!.state = TaskState.RUNNING
                val leader = vms.first()
                tasks[uuid]!!.leaderInstance = leader.instance
                leader.runCommand(taskType.getLeaderCommand(leader.ip), saveLogs = false)
                vms.drop(1).map { vm ->
                    scope.async {
                        vm.runCommand(taskType.getVmCommand(leader.ip, vm.ip), saveLogs = false)
                    }
                }
                LOGGER.info("All nodes launched kvas for task $uuid")
                clientVm.await().use { clientVm ->
                    clientVm.runCommand(
                        "sudo docker run --name kvas-client ghcr.io/bdse-class-2024/kvclient:51bee0409 --metadata-address=${leader.ip}:9000 loadtest --key-count=10",
                        saveLogs = true
                    )
                }
                LOGGER.info("Loadtest finished for task $uuid")
                vms.map { vm ->
                    scope.async {
                        vm.runCommand("sudo docker logs kvas-node", saveLogs = true)
                    }
                }.awaitAll()
                LOGGER.info("Logs saved for task $uuid")
                tasks[uuid]!!.state = TaskState.FINISHED
            }
            LOGGER.info("executed all commands for task $uuid")
        } catch (e: Exception) {
            tasks[uuid]!!.state = TaskState.FAILURE
            throw e
        }
    }

    override fun getTask(uuid: Uuid): Task? {
        return tasks[uuid]
    }

    override fun close() {
        vmRepository.close()
    }
}