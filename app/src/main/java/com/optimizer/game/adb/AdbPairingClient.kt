package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.optimizer.GameOptimizerEngine
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdbPairingClient {

    /**
     * Executes authentic Android 11+ Wireless ADB pairing handshake over TLSv1.3 with SPAKE2
     * using the native BoringSSL/libspake2 backend bundled via libadb-android.
     */
    suspend fun pair(
        context: Context,
        port: Int,
        pairingCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        var pairingCtx: PairingConnectionCtx? = null
        try {
            val keyPair = AdbKeyManager.getKeyPair(context)
            val cert = AdbKeyManager.getCertificate(context)
            val codeBytes = pairingCode.trim().toByteArray(Charsets.UTF_8)

            GameOptimizerEngine.log("Connecting to 127.0.0.1:$port for SPAKE2 pairing...", GameOptimizerEngine.LogType.COMMAND)

            pairingCtx = PairingConnectionCtx(
                "127.0.0.1",
                port,
                codeBytes,
                keyPair.private,
                cert,
                "FrameGuard"
            )

            pairingCtx.start()
            GameOptimizerEngine.log("SPAKE2 pairing handshake succeeded: RSA key trusted by adbd", GameOptimizerEngine.LogType.STDOUT)
            true
        } catch (e: Exception) {
            GameOptimizerEngine.log("[ERR] PairingConnectionCtx failed: ${e.message}", GameOptimizerEngine.LogType.ERROR)
            false
        } finally {
            try { pairingCtx?.close() } catch (_: Exception) {}
        }
    }
}
