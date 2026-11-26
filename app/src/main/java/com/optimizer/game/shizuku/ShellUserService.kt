package com.optimizer.game.shizuku

import com.optimizer.game.IShellService
import com.optimizer.game.privilege.ShellCommandRunner
import com.optimizer.game.privilege.ShellResult
import kotlin.system.exitProcess

/**
 * Privileged AIDL service executed under Android UID 2000 via Shizuku UserService.
 */
class ShellUserService : IShellService.Stub() {

    override fun runCommand(command: String): ShellResult =
        ShellCommandRunner.runCommand(command)

    override fun runArgs(args: Array<String>?): ShellResult {
        if (args.isNullOrEmpty()) {
            return ShellResult(exitCode = 2, stdout = "", stderr = "Empty argument list")
        }
        return ShellCommandRunner.runArgs(args.toList())
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        internal fun tokenizeCommand(command: String): List<String> =
            ShellCommandRunner.tokenizeCommand(command)
    }
}
