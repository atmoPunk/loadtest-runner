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
import net.schmizz.sshj.common.IOUtils
import java.io.File

interface VM : Closeable {
    suspend fun runCommand(cmd: String)
}

interface VMRepository : Closeable {
    suspend fun getVm(): VM
}

class VMImpl(val instance: String, private val ip: String, private val repo: VMRepositoryImpl) : VM {
    private val sshClient: SSHClient = SSHClient()

    companion object {
        val LOGGER = KtorSimpleLogger("kvas.VMImpl")
    }

    init {
        sshClient.addHostKeyVerifier(PromiscuousVerifier())
    }

    override suspend fun runCommand(cmd: String) {
        // if cmd is actually several commands saves only logs of the last one
        val filename = cmd.filterNot { it == '|' || it == '\'' || it == '/' }.replace(' ', '-')
        val outFile = "$filename.out.txt"
        val errFile = "$filename.err.txt"
        val redirected = "$cmd > '$outFile' 2> '$errFile'"
        runCommandImpl(redirected)
        runCommandImpl("gcloud storage cp '$outFile' '$errFile' gs://kvas-loadtester-logs/$instance/")
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
                sshClient.connect(ip, 22)
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
            addAccessConfigs(AccessConfig.newBuilder().apply {
                name = "External NAT"
                type = AccessConfig.Type.ONE_TO_ONE_NAT.toString()
                networkTier = AccessConfig.NetworkTier.STANDARD.toString()
            }.build())
        }.build()

        val serviceAccount = ServiceAccount.newBuilder().apply {
            email = "584796664311-compute@developer.gserviceaccount.com"
            addScopes("https://www.googleapis.com/auth/cloud-platform") // TODO: Limit scopes
        }

        val metadata = Metadata.newBuilder().apply {
            addItems(Items.newBuilder().apply {
                key = "ssh-keys"
                value = "$sshLogin:$sshPublicKey"
            }.build())
        }.build()

        val vmName = "kvas-${Uuid.random()}"
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