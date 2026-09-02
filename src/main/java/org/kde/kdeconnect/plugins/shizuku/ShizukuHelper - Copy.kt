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

/**
 * Central helper for Shizuku.
 * Uses reflection so the project still compiles if the Shizuku AAR is not
 * yet on the classpath; at runtime it works once the dependency is added
 * and Shizuku is running.
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE_PERMISSION = 0x5348 // "SH"

    @Volatile
    private var binderReady = false

    private var binderReceivedListener: Any? = null
    private var binderDeadListener: Any? = null

    fun init(context: Context) {
        try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")

            // OnBinderReceivedListener
            val receivedIface = Class.forName("rikka.shizuku.Shizuku\$OnBinderReceivedListener")
            binderReceivedListener = java.lang.reflect.Proxy.newProxyInstance(
                receivedIface.classLoader,
                arrayOf(receivedIface)
            ) { _, method, _ ->
                if (method.name == "onBinderReceived") {
                    binderReady = true
                    Log.i(TAG, "Shizuku binder received")
                }
                null
            }
            shizuku.getMethod("addBinderReceivedListenerSticky", receivedIface)
                .invoke(null, binderReceivedListener)

            // OnBinderDeadListener
            val deadIface = Class.forName("rikka.shizuku.Shizuku\$OnBinderDeadListener")
            binderDeadListener = java.lang.reflect.Proxy.newProxyInstance(
                deadIface.classLoader,
                arrayOf(deadIface)
            ) { _, method, _ ->
                if (method.name == "onBinderDead") {
                    binderReady = false
                    Log.w(TAG, "Shizuku binder dead")
                }
                null
            }
            shizuku.getMethod("addBinderDeadListener", deadIface)
                .invoke(null, binderDeadListener)

            val ping = shizuku.getMethod("pingBinder").invoke(null) as Boolean
            if (ping) binderReady = true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Shizuku library not on classpath – add dev.rikka.shizuku:api dependency")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init Shizuku listeners", e)
        }
    }

    fun destroy() {
        try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            binderReceivedListener?.let {
                val iface = Class.forName("rikka.shizuku.Shizuku\$OnBinderReceivedListener")
                shizuku.getMethod("removeBinderReceivedListener", iface).invoke(null, it)
            }
            binderDeadListener?.let {
                val iface = Class.forName("rikka.shizuku.Shizuku\$OnBinderDeadListener")
                shizuku.getMethod("removeBinderDeadListener", iface).invoke(null, it)
            }
        } catch (_: Throwable) {
        }
        binderReady = false
        binderReceivedListener = null
        binderDeadListener = null
    }

    fun isAvailable(): Boolean {
        return try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            shizuku.getMethod("pingBinder").invoke(null) as Boolean
        } catch (_: Throwable) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val result = shizuku.getMethod("checkSelfPermission").invoke(null) as Int
            result == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            val isPreV11 = try {
                shizuku.getMethod("isPreV11").invoke(null) as Boolean
            } catch (_: Throwable) {
                false
            }
            if (isPreV11) return
            shizuku.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, REQUEST_CODE_PERMISSION)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    fun getService(name: String): IBinder? {
        return try {
            if (!isAvailable() || !isPermissionGranted()) {
                Log.w(TAG, "Shizuku not ready for service $name")
                return null
            }
            val helper = Class.forName("rikka.shizuku.SystemServiceHelper")
            val raw = helper.getMethod("getSystemService", String::class.java).invoke(null, name) as? IBinder
                ?: return null
            val wrapperClass = Class.forName("rikka.shizuku.ShizukuBinderWrapper")
            wrapperClass.getConstructor(IBinder::class.java).newInstance(raw) as IBinder
        } catch (e: Throwable) {
            Log.e(TAG, "getService($name) failed", e)
            null
        }
    }

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
            val shizuku = Class.forName("rikka.shizuku.Shizuku")
            shizuku.getMethod("getUid").invoke(null) as Int
        } catch (_: Throwable) {
            -1
        }
    }

    fun isRoot(): Boolean = getUid() == 0
    fun isShell(): Boolean = getUid() == 2000
}
