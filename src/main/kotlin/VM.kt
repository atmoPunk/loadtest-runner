package kvas

import com.google.api.core.ApiFutureCallback
import com.google.cloud.compute.v1.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.google.api.core.ApiFutures
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.core.*
import kotlinx.coroutines.delay
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.IOUtils

interface VM : Closeable {
    suspend fun runCommand(cmd: String)
}

interface VMRepository : Closeable {
    suspend fun getVm(): VM
}

class VMImpl(val instance: String, private val ip: String, private val repo: VMRepositoryImpl) : VM {
    private val sshClient: SSHClient = SSHClient()
    private lateinit var sshSession: Session

    companion object {
        val LOGGER = KtorSimpleLogger("kvas.VMImpl")
    }

    init {
        sshClient.addHostKeyVerifier(PromiscuousVerifier())
    }

    override suspend fun runCommand(cmd: String) {
        if (! ::sshSession.isInitialized) {
            sshSession = establishSSHConnection()
        }
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

    private suspend fun establishSSHConnection(): Session {
        retry(3, {i, _ ->
            LOGGER.warn("$instance - could not establish ssh connection - attempt ${i + 1} out of 3")
        }) {
            sshClient.connect(ip, 22)
            sshClient.authPublickey("atmopunk", "/home/neuromancer/.ssh/id_ed25519")
        }
        return sshClient.startSession()
    }

    override fun close() {
        if (::sshSession.isInitialized) {
            sshSession.close()
        }
        sshClient.close()
        repo.shutdownVm(this)
    }
}

class VMRepositoryImpl : VMRepository {
    private val instancesClient = InstancesClient.create()

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun getVm(): VM {
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
        val vmName = "kvas-${Uuid.random()}"
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

        val op = instancesClient.insertAsync(request)
        val result = op.await()
        if (result.hasError()) {
            LOGGER.error("error while creating `$vmName`: ${result.error}")
            throw RuntimeException(result.error.toString())
        }

        val resultInstance = instancesClient.get("kvas-loadtester", "us-east1-b", vmName)
        val ip = resultInstance.networkInterfacesList.map { networkInterface ->
            networkInterface.networkIP
        }.first()

        LOGGER.info("instance `$vmName` ($ip) successfully created")
        return VMImpl(vmName, ip, this)
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