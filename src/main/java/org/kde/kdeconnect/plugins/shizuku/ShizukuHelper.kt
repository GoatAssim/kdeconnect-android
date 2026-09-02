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

/**
 * Central helper for Shizuku binder acquisition, permission checks and safe service access.
 * All privileged calls should go through this class so exceptions are handled uniformly.
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    const val REQUEST_CODE_PERMISSION = 0x5348 // "SH"

    @Volatile
    private var binderReady = false

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
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init Shizuku listeners", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        binderReady = false
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) {
                // Pre-v11 not really supported, but just in case
                return
            }
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    /**
     * Returns a system service binder wrapped with Shizuku privilege.
     * Returns null + logs if anything fails.
     */
    fun getService(name: String): IBinder? {
        return try {
            if (!isAvailable() || !isPermissionGranted()) {
                Log.w(TAG, "Shizuku not ready for service $name")
                return null
            }
            val raw = SystemServiceHelper.getSystemService(name) ?: return null
            ShizukuBinderWrapper(raw)
        } catch (e: Throwable) {
            Log.e(TAG, "getService($name) failed", e)
            null
        }
    }

    /**
     * Safe wrapper that runs a privileged block and converts any exception
     * into a human-readable error string (never throws to the caller).
     */
    fun <T> runPrivileged(block: () -> T): Result<T> {
        return try {
            if (!isAvailable()) {
                return Result.failure(IllegalStateException("Shizuku is not running"))
            }
            if (!isPermissionGranted()) {
                return Result.failure(SecurityException("Shizuku permission not granted"))
            }
            Result.success(block())
        } catch (e: Throwable) {
            Log.e(TAG, "Privileged call failed", e)
            Result.failure(e)
        }
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
