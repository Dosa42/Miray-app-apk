package com.example.automation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

data class TermuxExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val internalError: Int?,
    val errorMessage: String,
    val rawResult: String
)

object TermuxBridge {
    const val TERMUX_PACKAGE_NAME = "com.termux"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_RUN_COMMAND_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERR = "err"
    private const val RESULT_ERRMSG = "errmsg"

    const val DEFAULT_TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val DEFAULT_TERMUX_HOME = "/data/data/com.termux/files/home"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dispatches command to Termux via the official com.termux.RUN_COMMAND intent protocol.
     * Uses PendingIntent to robustly capture stdout, stderr, and exit code.
     */
    suspend fun executeInTermux(
        context: Context,
        command: String,
        workDir: String = DEFAULT_TERMUX_HOME,
        background: Boolean = true
    ): TermuxExecutionResult = suspendCancellableCoroutine { continuation ->
        var receiver: BroadcastReceiver? = null
        try {
            val requestId = UUID.randomUUID().toString()
            val resultAction = "com.example.TERMUX_RESULT_$requestId"

            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    try { c.unregisterReceiver(this) } catch (e: Exception) {}

                    val result = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
                    if (result == null) {
                        if (continuation.isActive) {
                            continuation.resume(failedResult("Termux returned no result bundle. Termux 0.109 or newer is required."))
                        }
                        return
                    }
                    val stdout = result.getString(RESULT_STDOUT, "")
                    val stderr = result.getString(RESULT_STDERR, "")
                    val exitCode = if (result.containsKey(RESULT_EXIT_CODE)) result.getInt(RESULT_EXIT_CODE) else null
                    val internalError = if (result.containsKey(RESULT_ERR)) result.getInt(RESULT_ERR) else null
                    val errorMessage = result.getString(RESULT_ERRMSG, "")

                    val rawResult = org.json.JSONObject()
                        .put("stdout", stdout)
                        .put("stderr", stderr)
                        .put("exitCode", exitCode ?: org.json.JSONObject.NULL)
                        .put("internalError", internalError ?: org.json.JSONObject.NULL)
                        .put("errorMessage", errorMessage)
                        .toString()
                    if (continuation.isActive) {
                        continuation.resume(
                            TermuxExecutionResult(
                                success = exitCode == 0 && internalError == -1 && errorMessage.isBlank(),
                                stdout = stdout,
                                stderr = stderr,
                                exitCode = exitCode,
                                internalError = internalError,
                                errorMessage = errorMessage,
                                rawResult = rawResult
                            )
                        )
                    }
                }
            }

            val filter = IntentFilter(resultAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val resultIntent = Intent(resultAction)
            resultIntent.setPackage(context.packageName)
            
            // FLAG_MUTABLE is required because Termux mutates the intent to add extras
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, requestId.hashCode(), resultIntent, pendingFlags)

            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE_NAME, "com.termux.app.RunCommandService")
                putExtra(EXTRA_RUN_COMMAND_PATH, DEFAULT_TERMUX_BASH_PATH)
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, background)
                putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0") // 0 = create new session
                putExtra(EXTRA_RUN_COMMAND_PENDING_INTENT, pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && background) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            continuation.invokeOnCancellation {
                try {
                    receiver?.let { context.unregisterReceiver(it) }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            try {
                receiver?.let { context.unregisterReceiver(it) }
            } catch (unregisterEx: Exception) {}
            
            if (continuation.isActive) {
                continuation.resume(failedResult("Failed to dispatch to Termux: ${e.localizedMessage ?: e.message}"))
            }
        }
    }

    private fun failedResult(message: String): TermuxExecutionResult = TermuxExecutionResult(
        success = false,
        stdout = "",
        stderr = "",
        exitCode = null,
        internalError = null,
        errorMessage = message,
        rawResult = org.json.JSONObject()
            .put("stdout", "")
            .put("stderr", "")
            .put("exitCode", org.json.JSONObject.NULL)
            .put("internalError", org.json.JSONObject.NULL)
            .put("errorMessage", message)
            .toString()
    )

    fun launchTermuxApp(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
