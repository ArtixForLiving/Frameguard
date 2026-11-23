package com.optimizer.game.privilege

import android.content.Context
import com.optimizer.game.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object PrivilegeManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND"))
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private var activeBackend: PrivilegeBackend = ShizukuManager
    private var nativeAdbBackend: PrivilegeBackend? = null

    fun registerNativeBackend(backend: PrivilegeBackend) {
        nativeAdbBackend = backend
        scope.launch {
            backend.state.collect { nativeState ->
                // If Shizuku is not connected, reflect native state
                if (!ShizukuManager.state.value.isConnected) {
                    _state.value = nativeState
                    if (nativeState.isConnected) {
                        activeBackend = backend
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (nativeAdbBackend == null) {
            registerNativeBackend(com.optimizer.game.adb.NativeAdbBackend(appContext))
        }
        ShizukuManager.init()
        scope.launch {
            ShizukuManager.state.collect { shizukuState ->
                if (shizukuState.isConnected) {
                    activeBackend = ShizukuManager
                    _state.value = shizukuState
                } else {
                    // Check if native adb backend is connected
                    val nativeState = nativeAdbBackend?.state?.value
                    if (nativeState != null && nativeState.isConnected) {
                        activeBackend = nativeAdbBackend!!
                        _state.value = nativeState
                    } else {
                        activeBackend = ShizukuManager
                        _state.value = shizukuState
                    }
                }
            }
        }
    }

    fun refreshState() {
        ShizukuManager.updateState()
        (nativeAdbBackend as? com.optimizer.game.adb.NativeAdbBackend)?.checkConnection()
        if (!ShizukuManager.state.value.isConnected) {
            val nativeState = nativeAdbBackend?.state?.value
            if (nativeState != null && nativeState.isConnected) {
                activeBackend = nativeAdbBackend!!
                _state.value = nativeState
                return
            }
        }
    }

    suspend fun exec(command: String): String {
        return activeBackend.exec(command)
    }

    fun disconnect() {
        activeBackend.disconnect()
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }
}
