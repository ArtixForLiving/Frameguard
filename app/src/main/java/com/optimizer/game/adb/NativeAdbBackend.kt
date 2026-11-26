package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.privilege.PrivilegeBackend
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeAdbBackend(private val context: Context) : PrivilegeBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND"))
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    val mdnsDiscovery = AdbMdnsDiscovery(context)
    private var watcherJob: Job? = null
    @Volatile
    private var lastBootstrapFailureMs: Long = 0L

    companion object {
        private const val BOOTSTRAP_FAILURE_COOLDOWN_MS = 5_000L
    }

    init {
        startBackgroundDiscovery()
    }

    private fun startBackgroundDiscovery() {
        mdnsDiscovery.startDiscovery()

        // 1. Observe mDNS discovered connect port
        scope.launch {
            mdnsDiscovery.connectPort.collect { port ->
                if (port != null) {
                    AdbPortStore.savePort(context, port)
                    if (!_state.value.isConnected && !isInBootstrapCooldown()) {
                        val started = NativeDaemonManager.ensureDaemonStarted(context, port)
                        if (started) {
                            _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                        } else {
                            lastBootstrapFailureMs = System.currentTimeMillis()
                        }
                    }
                }
            }
        }

        // 2. Fallback discovery via cached port / port scan only if mDNS hasn't connected within 1.2s
        scope.launch {
            if (NativeDaemonManager.isDaemonAlive(context)) {
                _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                return@launch
            }
            delay(1200)
            if (!_state.value.isConnected && mdnsDiscovery.connectPort.value == null) {
                val port = AdbPortScanner.findOpenPort(context)
                if (port != null && !_state.value.isConnected) {
                    val started = NativeDaemonManager.ensureDaemonStarted(context, port)
                    if (started) {
                        _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                    }
                }
            }
        }

        startWatcher()
    }

    private fun startWatcher() {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch {
            while (isActive) {
                delay(1200)
                if (!_state.value.isConnected && NativeDaemonManager.isDaemonAlive(context)) {
                    _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                }
            }
        }
    }

    fun checkConnection() {
        if (watcherJob == null || watcherJob?.isActive != true) {
            startWatcher()
        }
        scope.launch {
            val alive = NativeDaemonManager.isDaemonAlive(context)
            if (alive) {
                _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
            } else {
                val connectPort = mdnsDiscovery.connectPort.value ?: AdbPortStore.getPort(context)
                if (connectPort != null && !isInBootstrapCooldown()) {
                    val started = NativeDaemonManager.ensureDaemonStarted(context, connectPort)
                    if (started) {
                        _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                        return@launch
                    } else {
                        lastBootstrapFailureMs = System.currentTimeMillis()
                    }
                }
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            }
        }
    }

    private fun isInBootstrapCooldown(): Boolean =
        System.currentTimeMillis() - lastBootstrapFailureMs < BOOTSTRAP_FAILURE_COOLDOWN_MS

    override suspend fun exec(command: String): ShellResult =
        executeWithFallback(
            daemonExec = { NativeDaemonManager.exec(context, command) },
            adbFallbackExec = { port -> AdbTlsClient.executeCommand(context, port, command) }
        )

    override suspend fun execArgs(args: List<String>): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(exitCode = 2, stdout = "", stderr = "Empty argument list")
        }
        return executeWithFallback(
            daemonExec = { NativeDaemonManager.execArgs(context, args) },
            adbFallbackExec = { port ->
                val escapedCommand = args.joinToString(" ") { arg ->
                    "'" + arg.replace("'", "'\"'\"'") + "'"
                }
                AdbTlsClient.executeCommand(context, port, escapedCommand)
            }
        )
    }

    private suspend fun executeWithFallback(
        daemonExec: suspend () -> ShellResult,
        adbFallbackExec: suspend (Int) -> ShellResult
    ): ShellResult {
        if (NativeDaemonManager.isDaemonAlive(context)) {
            val result = daemonExec()
            if (result.transportError != null) {
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            }
            return result
        }

        val port = mdnsDiscovery.connectPort.value
            ?: AdbPortStore.getPort(context)
            ?: AdbPortScanner.findOpenPort(context)

        if (port != null) {
            val started = NativeDaemonManager.ensureDaemonStarted(context, port)
            val result = if (started) {
                _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                daemonExec()
            } else {
                adbFallbackExec(port)
            }
            if (result.transportError != null) {
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            }
            return result
        }

        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
        return ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            transportError = "Native ADB daemon not connected"
        )
    }

    override fun disconnect() {
        mdnsDiscovery.stopDiscovery()
        watcherJob?.cancel()
        watcherJob = null
        scope.coroutineContext.cancelChildren()
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }
}
