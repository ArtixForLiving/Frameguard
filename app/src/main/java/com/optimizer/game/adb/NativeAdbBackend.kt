package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.privilege.PrivilegeBackend
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NativeAdbBackend(private val context: Context) : PrivilegeBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND"))
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    val mdnsDiscovery = AdbMdnsDiscovery(context)

    init {
        mdnsDiscovery.startDiscovery()
        checkConnection()

        // 1. Observe mDNS discovered connect port
        scope.launch {
            mdnsDiscovery.connectPort.collect { port ->
                if (port != null) {
                    AdbPortStore.savePort(context, port)
                    if (!_state.value.isConnected) {
                        NativeDaemonManager.ensureDaemonStarted(context, port)
                        checkConnection()
                    }
                }
            }
        }

        // 2. Parallel background discovery via cached port and fast port scan
        scope.launch {
            if (!_state.value.isConnected) {
                // If daemon is already running, bind immediately
                if (NativeDaemonManager.isDaemonAlive(context)) {
                    _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                    return@launch
                }
                // Try cached port or scan loopback
                val port = AdbPortScanner.findOpenPort(context)
                if (port != null && !_state.value.isConnected) {
                    NativeDaemonManager.ensureDaemonStarted(context, port)
                    checkConnection()
                }
            }
        }

        // 3. Continuous watcher while UNBOUND so the UI switches to green in real-time
        // without requiring the user to kill or restart the app after pairing
        scope.launch {
            while (!_state.value.isConnected) {
                kotlinx.coroutines.delay(1200)
                if (NativeDaemonManager.isDaemonAlive(context)) {
                    _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                    break
                }
            }
        }
    }

    fun checkConnection() {
        scope.launch {
            val alive = NativeDaemonManager.isDaemonAlive(context)
            if (alive) {
                _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
            } else {
                val connectPort = mdnsDiscovery.connectPort.value ?: AdbPortStore.getPort(context)
                if (connectPort != null) {
                    val started = NativeDaemonManager.ensureDaemonStarted(context, connectPort)
                    if (started) {
                        _state.value = PrivilegeState(true, PrivilegeSource.NATIVE_ADB, "UID 2000 · ADB")
                        return@launch
                    }
                }
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            }
        }
    }

    override suspend fun exec(command: String): String {
        if (NativeDaemonManager.isDaemonAlive(context)) {
            return NativeDaemonManager.exec(context, command)
        }
        val port = mdnsDiscovery.connectPort.value ?: AdbPortStore.getPort(context) ?: AdbPortScanner.findOpenPort(context)
        if (port != null) {
            val started = NativeDaemonManager.ensureDaemonStarted(context, port)
            if (started) {
                return NativeDaemonManager.exec(context, command)
            }
            return AdbTlsClient.executeCommand(context, port, command)
        }
        return "ERROR: Native ADB daemon not connected"
    }

    override fun disconnect() {
        mdnsDiscovery.stopDiscovery()
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }
}
