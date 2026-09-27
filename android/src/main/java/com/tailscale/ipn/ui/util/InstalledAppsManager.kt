// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tailscale.ipn.BuildConfig

// isSystem marks preinstalled apps without a launcher entry (services, providers, etc.), which
// users rarely want to route individually.
data class InstalledApp(val name: String, val packageName: String, val isSystem: Boolean)

class InstalledAppsManager(
    val packageManager: PackageManager,
) {
  fun fetchInstalledApps(): List<InstalledApp> {
    return packageManager
        .getInstalledApplications(PackageManager.GET_META_DATA)
        .filter(appIsIncluded)
        .map {
          InstalledApp(
              name = it.loadLabel(packageManager).toString(),
              packageName = it.packageName,
              isSystem =
                  (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                      packageManager.getLaunchIntentForPackage(it.packageName) == null,
          )
        }
        .sortedBy { it.name }
  }

  // Some ROMs (Xiaomi MIUI/HyperOS) gate app listing behind their own runtime permission on top
  // of QUERY_ALL_PACKAGES. While it is denied, getInstalledApplications silently returns an
  // empty list instead of throwing.
  fun needsAppListPermission(context: Context): Boolean {
    val defined =
        try {
          packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
          true
        } catch (e: PackageManager.NameNotFoundException) {
          false
        }
    return defined &&
        ContextCompat.checkSelfPermission(context, GET_INSTALLED_APPS_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
  }

  private val appIsIncluded: (ApplicationInfo) -> Boolean = { app ->
    app.packageName != BuildConfig.APPLICATION_ID &&
        // Only show apps that can access the Internet
        packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) ==
            PackageManager.PERMISSION_GRANTED
  }

  companion object {
    const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
  }
}
