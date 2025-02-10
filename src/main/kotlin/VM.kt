@file:OptIn(ExperimentalUuidApi::class)

package kvas

import com.google.api.core.ApiFutureCallback
import com.google.cloud.compute.v1.*

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.google.api.core.ApiFutures
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.core.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import net.schmizz.sshj.common.IOUtils
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
sealed interface VM : Closeable {
    suspend fun runCommand(cmd: String, saveLogs: Boolean)
    val ip: String
    val externalIp: String
    val task: Uuid
    val instance: String
}

interface VMRepository : Closeable {
    suspend fun getVm(prefix: String, task: Uuid): VM
}

@Serializable
@SerialName("VMImpl")
data class VMImplRepr(val instance: String, val ip: String)

@OptIn(ExperimentalSerializationApi::class)
object VMImplSerializer : KSerializer<VMImpl> {
    override val descriptor = SerialDescriptor("kvas.VMImpl", VMImplRepr.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: VMImpl) {
        encoder.encodeSerializableValue(VMImplRepr.serializer(), VMImplRepr(value.instance, value.ip))
    }

    override fun deserialize(decoder: Decoder): VMImpl {
        throw UnsupportedOperationException("Deserialization is not supported for VMImpl")
    }
}

@Serializable(with = VMImplSerializer::class)
class VMImpl(override val instance: String, override val ip: String, override val externalIp: String, override val task: Uuid, private val repo: VMRepositoryImpl) : VM {
    private val sshClient: SSHClient = SSHClient()

    companion object {
        val LOGGER = KtorSimpleLogger("kvas.VMImpl")
    }

    init {
        sshClient.addHostKeyVerifier(PromiscuousVerifier())
    }

    override suspend fun runCommand(cmd: String, saveLogs: Boolean) {
        if (!saveLogs) {
            runCommandImpl(cmd)
            return
        }
        // if cmd is actually several commands saves only logs of the last one
        val filename = cmd.filterNot { it == '|' || it == '\'' || it == '/' }.replace(' ', '-')
        val outFile = "$filename.out.txt"
        val errFile = "$filename.err.txt"
        val redirected = "$cmd > '$outFile' 2> '$errFile'"
        try {
            runCommandImpl(redirected)
        } catch (e: RuntimeException) {
            runCommandImpl("gcloud storage cp '$outFile' '$errFile' gs://kvas-loadtester-logs/$task/$instance/")
            throw e
        }
        runCommandImpl("gcloud storage cp '$outFile' '$errFile' gs://kvas-loadtester-logs/$task/$instance/")
    }

    private suspend fun runCommandImpl(cmd: String) {
        if (!sshClient.isConnected) {
            establishSSHConnection()
        }
        sshClient.startSession().use { sshSession ->
            val exec = sshSession.exec(cmd)
            withContext(Dispatchers.IO) {
                exec.join()
                val out = IOUtils.readFully(exec.inputStream)
                LOGGER.info("$instance - cmd `$cmd`, out: $out")
            }
            if (exec.exitStatus != 0) {
                val err = withContext(Dispatchers.IO) {
                    IOUtils.readFully(exec.errorStream)
                }
                LOGGER.error("$instance - cmd `$cmd` exited with code ${exec.exitStatus}, err: $err")
                throw RuntimeException("$instance - $cmd exited with code ${exec.exitStatus}")
            }
        }
    }

    private suspend fun establishSSHConnection() {
        retry(3, {i, _ ->
            LOGGER.warn("$instance - could not establish ssh connection - attempt ${i + 1} out of 3")
        }) {
            if (!sshClient.isConnected) {
                sshClient.connect(externalIp, 22)
            }
            sshClient.authPublickey(repo.sshLogin, repo.sshKeyFile)
        }
    }

    override fun close() {
        sshClient.close()
        repo.shutdownVm(this)
    }
}

class VMRepositoryImpl : VMRepository {
    private val instancesClient = InstancesClient.create()
    private val sshPublicKey: String
    val sshKeyFile: String
    val sshLogin: String

    init {
        val sshLogin = System.getenv("SSH_LOGIN")
        if (sshLogin == null || sshLogin.isEmpty()) {
            throw RuntimeException("`SSH_LOGIN` env variable is not set")
        }
        val sshFile = System.getenv("SSH_FILE")
        if (sshFile == null || sshFile.isEmpty()) {
            throw RuntimeException("`SSH_FILE` env variable is not set")
        }
        this.sshLogin = sshLogin
        sshKeyFile = sshFile
        sshPublicKey = File("$sshFile.pub").readText()
    }

    override suspend fun getVm(prefix: String, task: Uuid): VM {
        val disk = AttachedDisk.newBuilder().apply {
            boot = true
            autoDelete = true
            type = AttachedDisk.Type.PERSISTENT.toString()
            deviceName = "disk-1"
            initializeParams = AttachedDiskInitializeParams.newBuilder().apply {
                sourceImage = "projects/kvas-loadtester/global/images/kvnode-base-image"
                diskSizeGb = 20L
            }.build()
        }.build()

        val network = NetworkInterface.newBuilder().apply {
            name = "default"
            addAccessConfigs(AccessConfig.newBuilder().apply {
                name = "External NAT"
                type = AccessConfig.Type.ONE_TO_ONE_NAT.toString()
                networkTier = AccessConfig.NetworkTier.STANDARD.toString()
            }.build())
        }.build()

        val serviceAccount = ServiceAccount.newBuilder().apply {
            // Service account with only object storage access
            email = "kvnode@kvas-loadtester.iam.gserviceaccount.com"
            addScopes("https://www.googleapis.com/auth/cloud-platform")
        }

        val metadata = Metadata.newBuilder().apply {
            addItems(Items.newBuilder().apply {
                key = "ssh-keys"
                value = "$sshLogin:$sshPublicKey"
            }.build())
        }.build()

        val vmName = "$prefix-${Uuid.random()}"
        val instance = Instance.newBuilder().apply {
            name = vmName
            machineType = "zones/us-east1-b/machineTypes/g1-small"
            this.metadata = metadata
            addDisks(disk)
            addNetworkInterfaces(network)
            addServiceAccounts(serviceAccount)
        }.build()

        val request = InsertInstanceRequest.newBuilder().apply {
            project = "kvas-loadtester"
            zone = "us-east1-b"
            instanceResource = instance
        }.build()

        val op = instancesClient.insertAsync(request)
        val result = op.await()
        if (result.hasError()) {
            LOGGER.error("error while creating `$vmName`: ${result.error}")
            throw RuntimeException(result.error.toString())
        }

        val resultInstance = retry(3, {_, e ->
            LOGGER.error("Could not get information about $vmName", e)
        }) {
            instancesClient.get("kvas-loadtester", "us-east1-b", vmName)
        }

        val (ip, externalIp) = resultInstance.networkInterfacesList.map { networkInterface ->
            Pair(networkInterface.networkIP, networkInterface.accessConfigsList.first().natIP)
        }.first()

        LOGGER.info("instance `$vmName` ($ip) successfully created for task $task")
        return VMImpl(vmName, ip, externalIp, task, this)
    }

    override fun close() {
        instancesClient.close()
    }

    fun shutdownVm(vm: VMImpl) {
        val request = DeleteInstanceRequest.newBuilder().apply {
            project = "kvas-loadtester"
            zone = "us-east1-b"
            this.instance = vm.instance
        }.build()
        val op = instancesClient.deleteAsync(request)
        ApiFutures.addCallback(op, ShutdownLogCallback<Operation>(vm.instance), MoreExecutors.directExecutor())
    }

    companion object {
        val LOGGER = KtorSimpleLogger("kvas.VMRepositoryImpl")

        private class ShutdownLogCallback<T>(val instance: String) : ApiFutureCallback<T> {
            override fun onSuccess(result: T?) {
                LOGGER.info("instance `$instance` successfully shut down")
            }

            override fun onFailure(t: Throwable) {
                LOGGER.error("error while shutting down `$instance`", t)
            }
        }
    }
}