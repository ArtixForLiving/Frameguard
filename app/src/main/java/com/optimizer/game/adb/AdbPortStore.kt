package com.optimizer.game.adb

import android.content.Context

object AdbPortStore {
    private const val PREFS_NAME = "frameguard_adb_prefs"
    private const val KEY_CONNECT_PORT = "last_known_connect_port"

    fun getPort(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_CONNECT_PORT, -1)
        return if (port in 1024..65535) port else null
    }

    fun savePort(context: Context, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CONNECT_PORT, port).apply()
    }
}
