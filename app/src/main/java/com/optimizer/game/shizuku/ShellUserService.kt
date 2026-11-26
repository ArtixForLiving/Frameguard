package com.optimizer.game.shizuku

import com.optimizer.game.IShellService
import com.optimizer.game.privilege.ShellResult
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Privileged AIDL service executed under Android UID 2000 via Shizuku UserService.
 */
class ShellUserService : IShellService.Stub() {

    override fun runCommand(command: String): ShellResult {
        val args = try {
            tokenizeCommand(command)
        } catch (e: IllegalArgumentException) {
            return ShellResult(exitCode = 2, stdout = "", stderr = e.message ?: "Invalid command")
        }
        return executeArgs(args)
    }

    override fun runArgs(args: Array<String>?): ShellResult {
        if (args.isNullOrEmpty()) {
            return ShellResult(exitCode = 2, stdout = "", stderr = "Empty argument list")
        }
        return executeArgs(args.toList())
    }

    private fun executeArgs(args: List<String>): ShellResult {
        return try {
            val process = ProcessBuilder(args).start()
            var stdErrText = ""
            val errThread = Thread {
                stdErrText = try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                } catch (_: Exception) {
                    ""
                }
            }.apply {
                isDaemon = true
                start()
            }

            val stdOutText = try {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            } catch (_: Exception) {
                ""
            }

            errThread.join()
            val exitCode = process.waitFor()
            ShellResult(
                exitCode = exitCode,
                stdout = stdOutText.trimEnd(),
                stderr = stdErrText.trimEnd()
            )
        } catch (e: Exception) {
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: "Command execution failed")
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        private val FORBIDDEN_METACHARS = charArrayOf(';', '|', '&', '`', '$', '>', '<', '\n', '\r')

        internal fun tokenizeCommand(command: String): List<String> {
            for (ch in command) {
                if (ch in FORBIDDEN_METACHARS) {
                    val label = when (ch) {
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        else -> ch.toString()
                    }
                    throw IllegalArgumentException("Rejected shell metacharacter: '$label'")
                }
            }

            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inSingleQuote = false
            var inDoubleQuote = false
            var escaping = false
            var hasToken = false

            for (ch in command) {
                when {
                    escaping -> {
                        current.append(ch)
                        escaping = false
                        hasToken = true
                    }
                    ch == '\\' && !inSingleQuote -> {
                        escaping = true
                        hasToken = true
                    }
                    ch == '\'' && !inDoubleQuote -> {
                        inSingleQuote = !inSingleQuote
                        hasToken = true
                    }
                    ch == '"' && !inSingleQuote -> {
                        inDoubleQuote = !inDoubleQuote
                        hasToken = true
                    }
                    ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                        if (hasToken) {
                            tokens.add(current.toString())
                            current.setLength(0)
                            hasToken = false
                        }
                    }
                    else -> {
                        current.append(ch)
                        hasToken = true
                    }
                }
            }

            if (escaping) {
                throw IllegalArgumentException("Trailing escape character in command")
            }
            if (inSingleQuote || inDoubleQuote) {
                throw IllegalArgumentException("Unterminated quote in command")
            }
            if (hasToken) {
                tokens.add(current.toString())
            }
            if (tokens.isEmpty()) {
                throw IllegalArgumentException("Empty command")
            }
            return tokens
        }
    }
}
