@file:OptIn(ExperimentalUuidApi::class)

package kvas

import com.google.cloud.storage.Storage
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.google.cloud.storage.StorageOptions;
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.util.concurrent.TimeUnit

@Serializable
data class LogURLs(val urls: List<String>)

interface LogStorage : Closeable {
    suspend fun getLogsURLs(taskId: Uuid): LogURLs
}

class LogStorageImpl : LogStorage {
    private val storage = StorageOptions.newBuilder().apply {
        setProjectId("kvas-loadtester")
    }.build().service

    override suspend fun getLogsURLs(taskId: Uuid): LogURLs {
        val blobs = storage.list("kvas-loadtester-logs", Storage.BlobListOption.prefix(taskId.toString()))
        return LogURLs(blobs.iterateAll().map { blob ->
            storage.signUrl(blob.asBlobInfo(), 3, TimeUnit.HOURS, Storage.SignUrlOption.withV4Signature()).toString()
        })
    }

    override fun close() {
        storage.close()
    }
}