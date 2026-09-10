package com.example.automation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val canRead: Boolean,
    val canWrite: Boolean
)

data class SystemDiagnostics(
    val androidVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val cpuArch: String,
    val totalRamMb: Long,
    val availRamMb: Long,
    val totalStorageGb: Double,
    val freeStorageGb: Double,
    val termuxInstalled: Boolean,
    val appFilesDir: String,
    val externalFilesDir: String
)

object FileSystemEngine {

    fun getSystemDiagnostics(context: Context): SystemDiagnostics {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)

        val internalStat = StatFs(Environment.getDataDirectory().path)
        val totalStorageGb = (internalStat.blockCountLong * internalStat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
        val freeStorageGb = (internalStat.availableBlocksLong * internalStat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)

        val appFiles = context.filesDir.absolutePath
        val extFiles = context.getExternalFilesDir(null)?.absolutePath ?: "Unavailable"

        return SystemDiagnostics(
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            apiLevel = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            cpuArch = Build.SUPPORTED_ABIS.joinToString(", "),
            totalRamMb = totalRamMb,
            availRamMb = availRamMb,
            totalStorageGb = String.format(Locale.US, "%.1f", totalStorageGb).toDouble(),
            freeStorageGb = String.format(Locale.US, "%.1f", freeStorageGb).toDouble(),
            termuxInstalled = TermuxBridge.isTermuxInstalled(context),
            appFilesDir = appFiles,
            externalFilesDir = extFiles
        )
    }

    suspend fun listDirectory(dirPath: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val dir = File(dirPath)
            if (!dir.exists()) {
                return@withContext Result.failure(Exception("Directory does not exist: $dirPath"))
            }
            if (!dir.isDirectory) {
                return@withContext Result.failure(Exception("Path is not a directory: $dirPath"))
            }

            val files = dir.listFiles() ?: emptyArray()
            val items = files.map { file ->
                FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified(),
                    canRead = file.canRead(),
                    canWrite = file.canWrite()
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readFile(path: String, maxBytes: Int = 100_000): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("File not found: $path"))
            }
            if (file.isDirectory) {
                return@withContext Result.failure(Exception("Target is a directory: $path"))
            }
            val text = file.readText()
            if (text.length > maxBytes) {
                Result.success(text.take(maxBytes) + "\n\n... [Truncated: File exceeded preview limit]")
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeFile(path: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            Result.success("File written successfully (${content.length} chars) to $path")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("File does not exist: $path"))
            }
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted) {
                Result.success("Successfully deleted $path")
            } else {
                Result.failure(Exception("Failed to delete $path (check permissions)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchFiles(baseDirPath: String, query: String, maxResults: Int = 50): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(baseDirPath)
            if (!baseDir.exists() || !baseDir.isDirectory) {
                return@withContext Result.failure(Exception("Invalid search directory: $baseDirPath"))
            }

            val matching = mutableListOf<FileItem>()
            baseDir.walkTopDown().maxDepth(5).forEach { file ->
                if (matching.size >= maxResults) return@forEach
                if (file.name.contains(query, ignoreCase = true)) {
                    matching.add(
                        FileItem(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified(),
                            canRead = file.canRead(),
                            canWrite = file.canWrite()
                        )
                    )
                }
            }
            Result.success(matching)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
