package com.example.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Build
import java.io.File

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val success: Boolean
)

object LocalProcessRunner {

    suspend fun execute(
        command: String,
        workDir: File? = null,
        timeoutMs: Long = 30000L
    ): ProcessResult = kotlinx.coroutines.coroutineScope {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val processBuilder = ProcessBuilder("sh", "-c", command)
                if (workDir != null && workDir.exists() && workDir.isDirectory) {
                    processBuilder.directory(workDir)
                }
                
                // Pass standard Android sandbox environment variables
                val env = processBuilder.environment()
                env["TERM"] = "xterm-256color"
                env["SHELL"] = "/system/bin/sh"

                val process = processBuilder.start()

                val stdoutJob = async(Dispatchers.IO) {
                    process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }

                val stderrJob = async(Dispatchers.IO) {
                    process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }

                val completedInTime = withTimeoutOrNull(timeoutMs) {
                    val exitCode = process.waitFor()
                    Triple(exitCode, stdoutJob.await(), stderrJob.await())
                }

                val duration = System.currentTimeMillis() - startTime

                if (completedInTime == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.destroyForcibly()
                    } else {
                        process.destroy()
                    }
                    ProcessResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = "Execution timed out after ${timeoutMs / 1000}s",
                        durationMs = duration,
                        success = false
                    )
                } else {
                    val (exitCode, stdout, stderr) = completedInTime
                    ProcessResult(
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr,
                        durationMs = duration,
                        success = (exitCode == 0)
                    )
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                ProcessResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Process execution error: ${e.localizedMessage ?: e.message}",
                    durationMs = duration,
                    success = false
                )
            }
        }
    }
}
