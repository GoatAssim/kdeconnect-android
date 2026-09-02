/*
 * SPDX-FileCopyrightText: 2026 Jarvis / KDE Connect Shizuku integration
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Privileged package management via Shizuku.
 * Supports listing, install (single APK path), uninstall.
 * Full split-APK / session installer can be added later.
 */
class PackageController(private val context: Context) {

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
                if (userOnly && (pi.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    return@forEach
                }
                val obj = JSONObject()
                obj.put("packageName", pi.packageName)
                obj.put("versionName", pi.versionName ?: "")
                obj.put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong())
                obj.put("label", pi.applicationInfo?.loadLabel(pm)?.toString() ?: "")
                arr.put(obj)
            }
            result.put("packages", arr)
            result.put("count", arr.length())
        } catch (e: Throwable) {
            result.put("error", e.message ?: "listPackages failed")
        }
        return result
    }

    /**
     * Install a single APK from a local path.
     * Requires Shizuku permission. Uses the shell identity.
     */
    fun installApk(path: String): JSONObject {
        val result = JSONObject()
        val r = ShizukuHelper.runPrivileged {
            // Simple approach: use `pm install` via Shizuku's shell-like execution
            // More robust session-based installer can be added later.
            val file = File(path)
            if (!file.exists()) {
                throw IllegalArgumentException("APK file does not exist: $path")
            }

            // Use Shizuku's newProcess / shell execution if available, otherwise reflection
            // For v13+ the recommended way is still the PackageInstaller session with
            // the Shizuku-wrapped IPackageManager, but the simplest reliable method is:
            val process = Shizuku.newProcess(arrayOf("pm", "install", "-r", "-d", path), null, null)
            val exit = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            Triple(exit, stdout, stderr)
        }

        return when {
            r.isSuccess -> {
                val (exit, stdout, stderr) = r.getOrThrow()
                result.put("success", exit == 0)
                result.put("exitCode", exit)
                result.put("stdout", stdout)
                result.put("stderr", stderr)
                if (exit != 0) {
                    result.put("error", stderr.ifEmpty { "pm install failed with code $exit" })
                }
                result
            }
            else -> {
                result.put("error", r.exceptionOrNull()?.message ?: "install failed")
                result
            }
        }
    }

    fun uninstall(packageName: String): JSONObject {
        val result = JSONObject()
        val r = ShizukuHelper.runPrivileged {
            val process = Shizuku.newProcess(arrayOf("pm", "uninstall", packageName), null, null)
            val exit = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            Triple(exit, stdout, stderr)
        }

        return when {
            r.isSuccess -> {
                val (exit, stdout, stderr) = r.getOrThrow()
                result.put("success", exit == 0)
                result.put("exitCode", exit)
                result.put("stdout", stdout)
                result.put("stderr", stderr)
                if (exit != 0) {
                    result.put("error", stderr.ifEmpty { "pm uninstall failed with code $exit" })
                }
                result
            }
            else -> {
                result.put("error", r.exceptionOrNull()?.message ?: "uninstall failed")
                result
            }
        }
    }
}
