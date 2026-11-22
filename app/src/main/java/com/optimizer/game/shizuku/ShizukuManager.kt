package com.optimizer.game.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.optimizer.game.BuildConfig
import com.optimizer.game.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuManager {
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 2000

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var shellService: IShellService? = null
    private var isBound = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                shellService = IShellService.Stub.asInterface(service)
                _state.value = ShizukuState.CONNECTED
            } else {
                _state.value = ShizukuState.UNAVAILABLE
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isBound = false
            updateState()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        isBound = false
        _state.value = ShizukuState.UNAVAILABLE
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindService()
                } else {
                    _state.value = ShizukuState.NO_PERMISSION
                }
            }
        }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        updateState()
    }

    fun cleanup() {
        unbindService()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
    }

    fun updateState() {
        if (!Shizuku.pingBinder()) {
            shellService = null
            isBound = false
            _state.value = ShizukuState.UNAVAILABLE
            return
        }

        if (Shizuku.isPreV11()) {
            _state.value = ShizukuState.UNAVAILABLE
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (shellService != null && shellService?.asBinder()?.isBinderAlive == true) {
                _state.value = ShizukuState.CONNECTED
            } else {
                bindService()
            }
        } else {
            _state.value = ShizukuState.NO_PERMISSION
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } else {
                bindService()
            }
        }
    }

    private fun bindService() {
        if (isBound && shellService != null && shellService?.asBinder()?.isBinderAlive == true) {
            _state.value = ShizukuState.CONNECTED
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            isBound = true
        } catch (e: Exception) {
            _state.value = ShizukuState.UNAVAILABLE
        }
    }

    private fun unbindService() {
        if (isBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (_: Exception) {}
            isBound = false
            shellService = null
        }
    }

    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        val service = shellService
        if (service == null || !service.asBinder().isBinderAlive) {
            return@withContext "ERROR: Shizuku ShellUserService not connected"
        }
        try {
            service.runCommand(cmd)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
