/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Package management. Uses normal PackageManager for listing.
 * Install/uninstall go through Shizuku when available (via reflection so we
 * still compile if the Shizuku dependency is missing).
 */
class PackageController(private val context: Context) {

    private val TAG = "PackageController"

    fun listPackages(userOnly: Boolean = true): JSONObject {
        val result = JSONObject()
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }

            val arr = JSONArray()
            packages.forEach { pi ->
                val appInfo = pi.applicationInfo
                if (userOnly && appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return@forEach
                }
                val obj = JSONObject()
                obj.put("packageName", pi.packageName)
                obj.put("versionName", pi.versionName ?: "")
                obj.put(
                    "versionCode",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
                )
                obj.put("label", appInfo?.loadLabel(pm)?.toString() ?: "")
                arr.put(obj)
            }
            result.put("packages", arr)
            result.put("count", arr.length())
        } catch (e: Throwable) {
            result.put("error", e.message ?: "listPackages failed")
        }
        return result
    }

    fun installApk(path: String): JSONObject {
        val result = JSONObject()
        try {
            val file = File(path)
            if (!file.exists()) {
                result.put("error", "APK file does not exist: $path")
                return result
            }

            val r = runPmViaShizuku(arrayOf("pm", "install", "-r", "-d", path))
            if (r == null) {
                result.put("error", "Shizuku not available – cannot install")
                return result
            }
            val (exit, stdout, stderr) = r
            result.put("success", exit == 0)
            result.put("exitCode", exit)
            result.put("stdout", stdout)
            result.put("stderr", stderr)
            if (exit != 0) {
                result.put("error", if (stderr.isNotEmpty()) stderr else "pm install failed with code $exit")
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "install failed")
            Log.e(TAG, "installApk failed", e)
        }
        return result
    }

    fun uninstall(packageName: String): JSONObject {
        val result = JSONObject()
        try {
            val r = runPmViaShizuku(arrayOf("pm", "uninstall", packageName))
            if (r == null) {
                result.put("error", "Shizuku not available – cannot uninstall")
                return result
            }
            val (exit, stdout, stderr) = r
            result.put("success", exit == 0)
            result.put("exitCode", exit)
            result.put("stdout", stdout)
            result.put("stderr", stderr)
            if (exit != 0) {
                result.put("error", if (stderr.isNotEmpty()) stderr else "pm uninstall failed with code $exit")
            }
        } catch (e: Throwable) {
            result.put("error", e.message ?: "uninstall failed")
            Log.e(TAG, "uninstall failed", e)
        }
        return result
    }

    /**
     * Runs a command via Shizuku.newProcess using reflection so this file
     * compiles even if the Shizuku library is not on the classpath yet.
     * Returns (exitCode, stdout, stderr) or null if Shizuku is unavailable.
     */
    private fun runPmViaShizuku(cmd: Array<String>): Triple<Int, String, String>? {
        return try {
            if (!ShizukuHelper.isAvailable() || !ShizukuHelper.isPermissionGranted()) {
                return null
            }
            // rikka.shizuku.Shizuku.newProcess(String[], String[], String)
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcess = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val process = newProcess.invoke(null, cmd, null, null) as Process
            val exit = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            Triple(exit, stdout, stderr)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Shizuku classes not found – add the dependency")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "runPmViaShizuku failed", e)
            null
        }
    }
}
