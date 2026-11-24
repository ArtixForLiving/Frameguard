package com.optimizer.game

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeManager
import com.optimizer.game.ui.MainScreen
import com.optimizer.game.ui.theme.FrameGuardTheme
import com.optimizer.game.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var recoveryJob: Job? = null

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission callback handled silently
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge transparent system bars with dark icons/content
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        PrivilegeManager.init(this)

        requestNotificationPermissionIfNeeded()
        checkGeometryRecovery()

        setContent {
            FrameGuardTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PrivilegeManager.refreshState()
        checkGeometryRecovery()
        window?.decorView?.postDelayed({
            PrivilegeManager.refreshState()
        }, 600)
    }

    private fun checkGeometryRecovery() {
        if (GameOptimizerEngine.loadGeometrySnapshot(this) == null) return
        if (recoveryJob?.isActive == true) return

        val targetPkg = GameOptimizerEngine.getActiveSessionPackage(this) ?: GameOptimizerEngine.DEFAULT_PACKAGE
        recoveryJob = lifecycleScope.launch {
            PrivilegeManager.state.collect { state ->
                if (state.isConnected) {
                    if (GameOptimizerEngine.loadGeometrySnapshot(this@MainActivity) != null) {
                        GameOptimizerEngine.log(
                            "Un-restored display geometry snapshot detected (reboot/crash recovery). Restoring defaults...",
                            GameOptimizerEngine.LogType.INFO
                        )
                        GameOptimizerEngine.restoreDefaults(targetPkg, this@MainActivity)
                    }
                    recoveryJob?.cancel()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(permission)
            }
        }
    }
}
