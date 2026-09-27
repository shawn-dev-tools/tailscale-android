// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelAppPickerViewModel : ViewModel() {
  val installedAppsManager = InstalledAppsManager(packageManager = App.get().packageManager)

  // null while the list is loading, so an empty result isn't mistaken for "still loading".
  val installedApps: StateFlow<List<InstalledApp>?> = MutableStateFlow(null)
  val needsAppListPermission: StateFlow<Boolean> = MutableStateFlow(false)
  val selectedPackageNames: StateFlow<List<String>> = MutableStateFlow(listOf())

  // Snapshot of the selection taken when the list loads, used to pin selected apps to the top.
  // It is not updated on every toggle so rows don't jump around while the user is checking them.
  private val pinnedPackageNames: StateFlow<Set<String>> = MutableStateFlow(setOf())
  val searchQuery: StateFlow<String> = MutableStateFlow("")
  val showSystemApps: StateFlow<Boolean> = MutableStateFlow(false)

  val visibleApps: StateFlow<List<InstalledApp>> =
      combine(
              installedApps,
              selectedPackageNames,
              pinnedPackageNames,
              searchQuery,
              showSystemApps,
          ) { apps, selected, pinned, query, showSystem ->
            val q = query.trim()
            apps
                .orEmpty()
                .filter { app ->
                  val visible =
                      showSystem ||
                          !app.isSystem ||
                          app.packageName in pinned ||
                          app.packageName in selected
                  visible &&
                      (q.isEmpty() ||
                          app.name.contains(q, ignoreCase = true) ||
                          app.packageName.contains(q, ignoreCase = true))
                }
                .sortedByDescending { it.packageName in pinned } // stable: keeps name order
          }
          .flowOn(Dispatchers.Default)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = listOf(),
          )

  private val iconCache = LruCache<String, ImageBitmap>(200)

  val allowSelected: StateFlow<Boolean> = MutableStateFlow(App.get().allowSelectedPackages())
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)
  val showSwitchDialog: StateFlow<Boolean> = MutableStateFlow(false)

  val mdmExcludedPackages: StateFlow<SettingState<String?>> = MDMSettings.excludedPackages.flow
  val mdmIncludedPackages: StateFlow<SettingState<String?>> = MDMSettings.includedPackages.flow

  private var saveJob: Job? = null
  private var loadJob: Job? = null

  init {
    loadInstalledApps()
  }

  private fun loadInstalledApps() {
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
      val (apps, needsPermission) =
          withContext(Dispatchers.IO) {
            installedAppsManager.fetchInstalledApps() to
                installedAppsManager.needsAppListPermission(App.get())
          }
      needsAppListPermission.set(needsPermission)
      installedApps.set(apps)
      initSelectedPackageNames(apps)
    }
  }

  // Called when the screen resumes or a permission request returns. Only reloads once the missing
  // permission has been granted, so it never resets a selection the user is still editing.
  fun reloadIfAppListPermissionGranted() {
    if (needsAppListPermission.value && !installedAppsManager.needsAppListPermission(App.get())) {
      installedApps.set(null)
      loadInstalledApps()
    }
  }

  private fun initSelectedPackageNames(apps: List<InstalledApp> = installedApps.value.orEmpty()) {
    allowSelected.set(App.get().allowSelectedPackages())
    selectedPackageNames.set(
        App.get()
            .selectedPackageNames()
            .let {
              if (!allowSelected.value) {
                it.union(App.get().builtInDisallowedPackageNames)
              } else {
                it
              }
            }
            .intersect(apps.map { it.packageName }.toSet())
            .toList()
    )
    pinnedPackageNames.set(selectedPackageNames.value.toSet())
  }

  fun cachedIcon(packageName: String): ImageBitmap? = iconCache.get(packageName)

  // Decoding app icons is slow, so it must stay off the main thread.
  suspend fun loadIcon(packageName: String, sizePx: Int): ImageBitmap? =
      withContext(Dispatchers.IO) {
        iconCache.get(packageName)
            ?: runCatching {
              installedAppsManager.packageManager
                  .getApplicationIcon(packageName)
                  .toBitmap(width = sizePx, height = sizePx)
                  .asImageBitmap()
            }
                .getOrNull()
                ?.also { iconCache.put(packageName, it) }
      }

  fun performSelectionSwitch() {
    App.get().switchUserSelectedPackages()
    initSelectedPackageNames()
  }

  fun select(packageName: String) {
    if (selectedPackageNames.value.contains(packageName)) return

    selectedPackageNames.set(selectedPackageNames.value + packageName)
    debounceSave()
  }

  fun deselect(packageName: String) {
    selectedPackageNames.set(selectedPackageNames.value - packageName)
    debounceSave()
  }

  private fun debounceSave() {
    saveJob?.cancel()
    saveJob = viewModelScope.launch {
      delay(500) // Wait to batch multiple rapid updates
      App.get().updateUserSelectedPackages(selectedPackageNames.value)
    }
  }
}
