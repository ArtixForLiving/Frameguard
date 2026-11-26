package com.optimizer.game.adb

import com.optimizer.game.privilege.ShellResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared mutual challenge-response authentication and length-prefixed binary framing
 * for DaemonServer and NativeDaemonManager.
 */
object DaemonProtocol {

    const val FRAME_PING: Byte = 0x01
    const val FRAME_EXEC: Byte = 0x02
    const val FRAME_EXEC_ARGS: Byte = 0x03
    const val FRAME_SHUTDOWN: Byte = 0x04

    const val AUTH_FAIL: Byte = 0x00
    const val AUTH_OK: Byte = 0x01

    private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    private const val NONCE_BYTES = 16
    private val secureRandom = SecureRandom()

    fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeProof(token: String, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    fun verifyProof(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun writeBytesFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
    }

    fun writeStringFrame(out: DataOutputStream, value: String) {
        writeBytesFrame(out, value.toByteArray(Charsets.UTF_8))
    }

    fun readBytesFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Invalid frame length: $length")
        }
        val bytes = ByteArray(length)
        if (length > 0) {
            input.readFully(bytes)
        }
        return bytes
    }

    fun readStringFrame(input: DataInputStream): String =
        String(readBytesFrame(input), Charsets.UTF_8)

    fun writeResultFrame(out: DataOutputStream, result: ShellResult) {
        out.writeInt(result.exitCode)
        writeStringFrame(out, result.stdout)
        writeStringFrame(out, result.stderr)
        out.flush()
    }

    fun readResultFrame(input: DataInputStream): ShellResult {
        val exitCode = input.readInt()
        val stdout = readStringFrame(input)
        val stderr = readStringFrame(input)
        return ShellResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    /**
     * Client side of mutual challenge-response:
     * sends clientNonce, verifies serverProof BEFORE sending clientProof, then checks AUTH_OK.
     */
    fun authenticateClient(
        input: DataInputStream,
        out: DataOutputStream,
        token: String
    ): Boolean {
        val clientNonce = generateNonce()
        writeStringFrame(out, clientNonce)
        out.flush()

        val serverNonce = readStringFrame(input)
        if (serverNonce.isEmpty()) return false
        val serverProof = readBytesFrame(input)

        val expectedServerProof = computeProof(token, "SERVER:$clientNonce:$serverNonce")
        if (!verifyProof(expectedServerProof, serverProof)) {
            return false
        }

        val clientProof = computeProof(token, "CLIENT:$clientNonce:$serverNonce")
        writeBytesFrame(out, clientProof)
        out.flush()

        return input.readByte() == AUTH_OK
    }

    /**
     * Server side of mutual challenge-response:
     * receives clientNonce, replies with serverNonce + serverProof, verifies clientProof.
     */
    fun authenticateServer(
        input: DataInputStream,
        out: DataOutputStream,
        token: String
    ): Boolean {
        val clientNonce = readStringFrame(input)
        if (clientNonce.length !in 8..128) {
            out.writeByte(AUTH_FAIL.toInt())
            out.flush()
            return false
        }

        val serverNonce = generateNonce()
        val serverProof = computeProof(token, "SERVER:$clientNonce:$serverNonce")
        val expectedClientProof = computeProof(token, "CLIENT:$clientNonce:$serverNonce")

        writeStringFrame(out, serverNonce)
        writeBytesFrame(out, serverProof)
        out.flush()

        val clientProof = readBytesFrame(input)
        val isValid = verifyProof(expectedClientProof, clientProof)
        out.writeByte(if (isValid) AUTH_OK.toInt() else AUTH_FAIL.toInt())
        out.flush()
        return isValid
    }
}
