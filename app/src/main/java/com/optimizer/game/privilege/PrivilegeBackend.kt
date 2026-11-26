package com.optimizer.game.privilege

import kotlinx.coroutines.flow.StateFlow

enum class PrivilegeSource {
    NONE,
    NATIVE_ADB,
    SHIZUKU
}

data class PrivilegeState(
    val isConnected: Boolean = false,
    val source: PrivilegeSource = PrivilegeSource.NONE,
    val statusMessage: String = "UNBOUND"
)

interface PrivilegeBackend {
    val state: StateFlow<PrivilegeState>
    suspend fun exec(command: String): String
    suspend fun execResult(command: String): ShellResult {
        val out = exec(command)
        return if (out.startsWith("ERROR:")) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                transportError = out.removePrefix("ERROR:").trim()
            )
        } else {
            ShellResult(exitCode = 0, stdout = out, stderr = "")
        }
    }

    suspend fun execArgs(args: List<String>): ShellResult =
        execResult(args.joinToString(" "))

    fun disconnect()
}
