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
    suspend fun exec(command: String): ShellResult
    suspend fun execArgs(args: List<String>): ShellResult
    fun disconnect()
}
