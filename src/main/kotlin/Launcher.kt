@file:OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)

package kvas

import com.google.api.gax.longrunning.OperationFuture
import com.google.cloud.compute.v1.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class LaunchResult(val uuid: Uuid)

typealias VmOperation = Pair<String, OperationFuture<Operation, Operation>>
typealias OperationsMap = MutableMap<String, OperationFuture<Operation, Operation>>

@Serializable
sealed interface Task {
    fun canTransit(): Boolean
    suspend fun transit(launcher: Launcher): Task
    fun isFinished(): Boolean
}

@Serializable
@SerialName("LaunchTask")
class LaunchTaskSurrogate(val launchedVms: ArrayList<String>, val launchingVms: List<String>)

object LaunchTaskSerializer : KSerializer<LaunchTask> {
    override val descriptor: SerialDescriptor = SerialDescriptor("kvas.LaunchTask", LaunchTaskSurrogate.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: LaunchTask) {
        val surrogate = LaunchTaskSurrogate(value.launchedVms, value.launchOperations.keys.toList())
        encoder.encodeSerializableValue(LaunchTaskSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): LaunchTask {
        throw UnsupportedOperationException("Deserialization is not supported")
    }
}


@Serializable(with = LaunchTaskSerializer::class)
class LaunchTask(val launchOperations: OperationsMap) : Task {
    val launchedVms = ArrayList<String>()

    override fun canTransit(): Boolean {
        launchOperations.filter { (_, op) -> op.isDone }.forEach { (vm, operation) ->
            launchOperations.remove(vm)
            val result = operation.get()
            if (result.hasError()) {
                Logger.getGlobal().severe(result.error.toString())
            } else {
                launchedVms.add(vm)
            }
        }
        return launchOperations.isEmpty()
    }

    override fun isFinished(): Boolean {
        return false
    }

    override suspend fun transit(launcher: Launcher): Task {
        return RunTask.create(launcher, launchedVms)
    }
}

@Serializable
@SerialName("RunTask")
class RunTaskSurrogate(val vms: List<String>)

object RunTaskSerializer : KSerializer<RunTask> {
    override val descriptor: SerialDescriptor = SerialDescriptor("kvas.RunTask", RunTaskSurrogate.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: RunTask) {
        val surrogate = RunTaskSurrogate(value.vms)
        encoder.encodeSerializableValue(RunTaskSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): RunTask {
        throw UnsupportedOperationException("Deserialization is not supported")
    }
}

@Serializable(with = RunTaskSerializer::class)
class RunTask(val vms: List<String>, private val sessions: List<Session>, private val commands: List<String>, private val execs: MutableList<Pair<Int, Session.Command?>>) : Task {
    companion object {
        fun create(launcher: Launcher, vms: List<String>): RunTask {
            val execs: MutableList<Pair<Int, Session.Command?>> = vms.map {
                Pair(-1, null)
            }.toMutableList()
            val sessions = vms.map {
                launcher.createSession(it)
            }
            val commands = listOf("ping -c 1 google.com")
            return RunTask(vms, sessions, commands, execs)
        }
    }

    override fun canTransit(): Boolean {
        var allFinished = true
        val totalVms = execs.size
        for (i in 0..<totalVms) {
            val cur = execs[i]
            val session = sessions[i]
            if (cur.first == -1) {
                allFinished = false
                val cmd = session.exec(commands[0])
                Logger.getGlobal().info("${vms[i]} started command ${commands[0]}")
                execs[i] = Pair(0, cmd)
            } else if (cur.first == commands.size) {
                // finished all commands
                continue
            } else {
                allFinished = false
                val curCmd = cur.second!!
                if (curCmd.isOpen) {
                    // current command still running
                    continue
                }
                val result = IOUtils.readFully(curCmd.inputStream)
                Logger.getGlobal().info("${vms[i]} finished command ${commands[cur.first]}, result: $result")
                curCmd.join()
                if (cur.first == commands.size - 1) {
                    execs[i] = Pair(commands.size, null)
                    continue
                }
                val cmd = session.exec(commands[cur.first + 1])
                Logger.getGlobal().info("${vms[i]} started command ${commands[cur.first + 1]}")
                execs[i] = Pair(cur.first + 1, cmd)
            }
        }
        return allFinished
    }

    override suspend fun transit(launcher: Launcher): Task {
        sessions.forEach { session ->
            session.close()
        }
        return ShutdownTask.create(launcher, vms)
    }

    override fun isFinished(): Boolean {
        return false
    }
}

@Serializable
@SerialName("ShutdownTask")
class ShutdownTaskSurrogate(val shutdownVms: ArrayList<String>, val shuttingDownVms: List<String>)

object ShutdownTaskSerializer : KSerializer<ShutdownTask> {
    override val descriptor: SerialDescriptor = SerialDescriptor("kvas.ShutdownTask", ShutdownTaskSurrogate.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: ShutdownTask) {
        val surrogate = ShutdownTaskSurrogate(value.shutdownVms, value.shutdownOperations.keys.toList())
        encoder.encodeSerializableValue(ShutdownTaskSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ShutdownTask {
        throw UnsupportedOperationException("Deserialization is not supported")
    }
}

@Serializable(with = ShutdownTaskSerializer::class)
class ShutdownTask(val shutdownOperations: OperationsMap): Task {
    val shutdownVms = ArrayList<String>()

    companion object {
        fun create(launcher: Launcher, vms: List<String>): ShutdownTask {
            val operations = vms.associate { vm -> launcher.shutdownVm(vm) }.toMutableMap()
            return ShutdownTask(operations)
        }
    }

    override fun canTransit(): Boolean {
        return false
    }

    override fun isFinished(): Boolean {
        shutdownOperations.filter { (_, op) -> op.isDone }.forEach { (vm, operation) ->
            shutdownOperations.remove(vm)
            val result = operation.get()
            if (result.hasError()) {
                Logger.getGlobal().severe(result.error.toString())
            } else {
                shutdownVms.add(vm)
            }
        }
        return shutdownOperations.isEmpty()
    }

    override suspend fun transit(launcher: Launcher): Task {
        throw UnsupportedOperationException("ShutdownTask has no next state")
    }
}

interface Launcher : Closeable {
    fun launchTask(image: String, nodeCount: Int): LaunchResult
    fun getTask(taskId: Uuid): Task?

    fun createSession(vm: String): Session
    fun launchVm(task: Uuid, index: Int): VmOperation
    fun shutdownVm(instance: String): VmOperation
}

class LauncherImpl: Launcher {
    companion object {
        val logger = Logger.getLogger("LauncherImpl")
    }

    val tasks = ConcurrentHashMap<Uuid, Task>()
    val instancesClient = InstancesClient.create()
    val sshClient = SSHClient()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    init {
        scope.launch {
            while (true) {
                val finished = ArrayList<Uuid>()
                val transitable = ArrayList<Uuid>()
                tasks.forEach { (uuid, task) ->
                        if (task.isFinished()) {
                            logger.info("task $uuid finished")
                            finished.add(uuid)
                        } else if (task.canTransit()) {
                            logger.info("task $uuid can transit")
                            transitable.add(uuid)
                        }
                }
                finished.forEach { tasks.remove(it) }
                transitable.forEach { tasks[it] = tasks[it]!!.transit(this@LauncherImpl) }
            }
        }
    }

    override fun launchTask(image: String, nodeCount: Int): LaunchResult {
        val uuid = Uuid.random()
        logger.info("launching task $uuid")
        val launchedVms = (0..<nodeCount).map { launchVm(uuid, it) }.toTypedArray<VmOperation>()
        tasks[uuid] = LaunchTask(mutableMapOf(*launchedVms))
        return LaunchResult(uuid)
    }

    override fun getTask(taskId: Uuid): Task? {
        logger.info("getting task $taskId")
        logger.info("current tasks: ${tasks.keys().toList().joinToString(", ", "[", "]")}")
        return tasks[taskId]
    }

    override fun close() {
        instancesClient.close()
    }

    override fun createSession(vm: String): Session {
        val instance = instancesClient.get("kvas-loadtester", "us-east1-b", vm)
        val ip = instance.networkInterfacesList.map { networkInterface ->
            networkInterface.networkIP
        }.first()

        sshClient.connect(ip)
        sshClient.authPublickey("neuromancer", "/Users/neuromancer/id_rsa.pub")
        return sshClient.startSession()
    }

    override fun launchVm(task: Uuid, index: Int): VmOperation  {
        val disk = AttachedDisk.newBuilder().apply {
            boot = true
            autoDelete = true
            type = AttachedDisk.Type.PERSISTENT.toString()
            deviceName = "disk-1"
            initializeParams = AttachedDiskInitializeParams.newBuilder().apply {
                sourceImage = "projects/debian-cloud/global/images/family/debian-12"
                diskSizeGb = 10L
            }.build()
        }.build()

        val network = NetworkInterface.newBuilder().apply {
            name = "default"
        }.build()

        val vmName = "test-instance-$task-$index"
        val instance = Instance.newBuilder().apply {
            name = vmName
            machineType = "zones/us-east1-b/machineTypes/g1-small"
            addDisks(disk)
            addNetworkInterfaces(network)
        }.build()

        val request = InsertInstanceRequest.newBuilder().apply {
            project = "kvas-loadtester"
            zone = "us-east1-b"
            instanceResource = instance
        }.build()

        return Pair(vmName, instancesClient.insertAsync(request))
    }

    override fun shutdownVm(instance: String): VmOperation {
        val request = DeleteInstanceRequest.newBuilder().apply {
            project = "kvas-loadtester"
            zone = "us-east1-b"
            this.instance = instance
        }.build()

        return Pair(instance, instancesClient.deleteAsync(request))
    }
}