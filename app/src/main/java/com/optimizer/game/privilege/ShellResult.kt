package com.optimizer.game.privilege

import android.os.Parcel
import android.os.Parcelable

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val transportError: String? = null
) : Parcelable {

    val isOk: Boolean
        get() = transportError == null && !timedOut && exitCode == 0

    constructor(parcel: Parcel) : this(
        exitCode = parcel.readInt(),
        stdout = parcel.readString().orEmpty(),
        stderr = parcel.readString().orEmpty(),
        timedOut = parcel.readByte() != 0.toByte(),
        transportError = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
        parcel.writeByte(if (timedOut) 1 else 0)
        parcel.writeString(transportError)
    }

    override fun describeContents(): Int = 0

    /**
     * Temporary bridge for callers still using PrivilegeManager.exec(String).
     */
    fun toLegacyString(): String {
        if (transportError != null) return "ERROR: $transportError"
        if (timedOut) return "ERROR: Command timed out"
        return stdout
    }

    companion object CREATOR : Parcelable.Creator<ShellResult> {
        override fun createFromParcel(parcel: Parcel): ShellResult = ShellResult(parcel)
        override fun newArray(size: Int): Array<ShellResult?> = arrayOfNulls(size)
    }
}
