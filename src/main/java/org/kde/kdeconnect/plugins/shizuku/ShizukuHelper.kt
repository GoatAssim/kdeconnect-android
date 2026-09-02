/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE_PERMISSION = 0x5348

    @Volatile
    private var binderReady = false

    private var newProcessMethod: Method? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderReady = true
        Log.i(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderReady = false
        Log.w(TAG, "Shizuku binder dead")
    }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            if (Shizuku.pingBinder()) {
                binderReady = true
            }
            // Cache private newProcess via reflection (public API removed/hidden)
            try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                newProcessMethod = m
            } catch (e: Throwable) {
                Log.e(TAG, "Could not access Shizuku.newProcess", e)
                newProcessMethod = null
            }
            Log.i(
                TAG,
                "init done available=${isAvailable()} permission=${isPermissionGranted()} uid=${getUid()} newProcess=${newProcessMethod != null}"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init Shizuku", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        binderReady = false
        newProcessMethod = null
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            Log.w(TAG, "isAvailable failed", e)
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Log.w(TAG, "isPermissionGranted failed", e)
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) return
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    fun getService(name: String): IBinder? {
        return try {
            if (!isAvailable() || !isPermissionGranted()) return null
            val raw = SystemServiceHelper.getSystemService(name) ?: return null
            ShizukuBinderWrapper(raw)
        } catch (e: Throwable) {
            Log.e(TAG, "getService($name) failed", e)
            null
        }
    }

    /** Why shell cannot run right now (null = OK to run). */
    fun notReadyReason(): String? {
        return try {
            if (!Shizuku.pingBinder()) {
                "Shizuku binder not connected (is Shizuku app running?)"
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                "Shizuku permission not granted to KDE Connect (open Shizuku app → Apps → allow KDE Connect)"
            } else if (newProcessMethod == null) {
                // try resolve again
                try {
                    val m = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    m.isAccessible = true
                    newProcessMethod = m
                    null
                } catch (e: Throwable) {
                    "Shizuku.newProcess not accessible: ${e.message}"
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            "Shizuku API error: ${e.message}"
        }
    }

    /**
     * (exitCode, stdout, stderr) or null if Shizuku not ready.
     */
    fun runShell(vararg cmd: String): Triple<Int, String, String>? {
        val reason = notReadyReason()
        if (reason != null) {
            Log.w(TAG, "runShell blocked: $reason | cmd=${cmd.joinToString(" ")}")
            return null
        }

        val method = newProcessMethod ?: return null

        return try {
            val process = method.invoke(null, cmd, null, null) as Process
            val exit = process.waitFor()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "runShell exit=$exit cmd=${cmd.joinToString(" ")} stderr=$stderr")
            Triple(exit, stdout, stderr)
        } catch (e: Throwable) {
            Log.e(TAG, "runShell exception cmd=${cmd.joinToString(" ")}", e)
            // Still return a triple so callers can show the exception text
            Triple(-1, "", e.message ?: "runShell exception")
        }
    }

    fun runShellFirstSuccess(vararg variants: Array<String>): Triple<Int, String, String>? {
        if (notReadyReason() != null) return null
        var last: Triple<Int, String, String>? = null
        for (cmd in variants) {
            val r = runShell(*cmd) ?: return null
            last = r
            if (r.first == 0) return r
        }
        return last
    }

    fun getUid(): Int {
        return try {
            Shizuku.getUid()
        } catch (_: Throwable) {
            -1
        }
    }

    fun isRoot(): Boolean = getUid() == 0
    fun isShell(): Boolean = getUid() == 2000
}