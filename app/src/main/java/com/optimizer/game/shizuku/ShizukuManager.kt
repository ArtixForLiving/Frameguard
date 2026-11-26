package com.optimizer.game.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.optimizer.game.BuildConfig
import com.optimizer.game.IShellService
import com.optimizer.game.privilege.PrivilegeBackend
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuManager : PrivilegeBackend {
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 2000

    private val _state = MutableStateFlow(PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND"))
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var isBound = false

    private val listenersRegistered = AtomicBoolean(false)

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
                isBound = true
                _state.value = PrivilegeState(true, PrivilegeSource.SHIZUKU, "UID 2000 · SHIZUKU")
            } else {
                shellService = null
                isBound = false
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
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
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindService()
                } else {
                    _state.value = PrivilegeState(false, PrivilegeSource.NONE, "NO PERM")
                }
            }
        }

    fun init() {
        if (listenersRegistered.compareAndSet(false, true)) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        updateState()
    }

    fun cleanup() {
        unbindService()
        if (listenersRegistered.compareAndSet(true, false)) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            } catch (_: Exception) {}
        }
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }

    override fun disconnect() {
        // Keep Shizuku listeners registered so future reconnects/permission grants still work
        unbindService()
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
    }

    fun updateState() {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            shellService = null
            isBound = false
            _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            return
        }

        if (Shizuku.isPreV11()) {
            _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (shellService != null && shellService?.asBinder()?.isBinderAlive == true) {
                _state.value = PrivilegeState(true, PrivilegeSource.SHIZUKU, "UID 2000 · SHIZUKU")
            } else {
                bindService()
            }
        } else {
            _state.value = PrivilegeState(false, PrivilegeSource.NONE, "NO PERM")
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
            _state.value = PrivilegeState(true, PrivilegeSource.SHIZUKU, "UID 2000 · SHIZUKU")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            isBound = true
        } catch (_: Exception) {
            isBound = false
            shellService = null
            _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
        }
    }

    private fun unbindService() {
        if (isBound || shellService != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (_: Exception) {}
            isBound = false
            shellService = null
        }
    }

    override suspend fun exec(command: String): ShellResult =
        invokeRemote { service -> service.runCommand(command) }

    override suspend fun execArgs(args: List<String>): ShellResult =
        invokeRemote { service -> service.runArgs(args.toTypedArray()) }

    private suspend fun invokeRemote(call: (IShellService) -> ShellResult): ShellResult =
        withContext(Dispatchers.IO) {
            val service = shellService
            if (service == null || !service.asBinder().isBinderAlive) {
                shellService = null
                isBound = false
                _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
                updateState()
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    transportError = "Shizuku ShellUserService not connected"
                )
            }
            try {
                call(service)
            } catch (e: DeadObjectException) {
                handleDeadBinder(e)
            } catch (e: RemoteException) {
                handleDeadBinder(e)
            } catch (e: Exception) {
                ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    transportError = e.message ?: "Shizuku remote execution error"
                )
            }
        }

    private fun handleDeadBinder(e: RemoteException): ShellResult {
        shellService = null
        isBound = false
        _state.value = PrivilegeState(false, PrivilegeSource.NONE, "UNBOUND")
        updateState()
        return ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            transportError = e.message ?: e.javaClass.simpleName
        )
    }
}
