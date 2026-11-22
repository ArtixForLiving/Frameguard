package com.optimizer.game.shizuku

import com.optimizer.game.IShellService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Privileged AIDL service executed under Android UID 2000 via Shizuku UserService.
 */
class ShellUserService : IShellService.Stub() {

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdOut = BufferedReader(InputStreamReader(process.inputStream))
            val stdErr = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()

            var line: String?
            while (stdOut.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (stdErr.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            process.waitFor()
            output.toString().trimEnd()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
