// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.searchBarColors
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.SplitTunnelAppPickerViewModel

@Composable
fun SplitTunnelAppPickerView(
    backToSettings: BackNavigation,
    model: SplitTunnelAppPickerViewModel = viewModel(),
) {
  val installedApps by model.installedApps.collectAsState()
  val visibleApps by model.visibleApps.collectAsState()
  val searchQuery by model.searchQuery.collectAsState()
  val selectedPackageNames by model.selectedPackageNames.collectAsState()
  val allowSelected by model.allowSelected.collectAsState()
  val builtInDisallowedPackageNames: List<String> = App.get().builtInDisallowedPackageNames
  val mdmIncludedPackages by model.mdmIncludedPackages.collectAsState()
  val mdmExcludedPackages by model.mdmExcludedPackages.collectAsState()
  val showHeaderMenu by model.showHeaderMenu.collectAsState()
  val showSwitchDialog by model.showSwitchDialog.collectAsState()
  val needsAppListPermission by model.needsAppListPermission.collectAsState()
  val iconSize = 40.dp
  val iconSizePx = with(LocalDensity.current) { iconSize.roundToPx() }
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.reloadIfAppListPermissionGranted()
      }
  var askedForAppListPermission by rememberSaveable { mutableStateOf(false) }

  // Ask once automatically; the notice's buttons cover later attempts.
  LaunchedEffect(needsAppListPermission) {
    if (needsAppListPermission && !askedForAppListPermission) {
      askedForAppListPermission = true
      permissionLauncher.launch(InstalledAppsManager.GET_INSTALLED_APPS_PERMISSION)
    }
  }
  // Pick up a permission granted from system settings when the user comes back.
  LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      model.reloadIfAppListPermissionGranted()
    }
  }

  if (showSwitchDialog) {
    SwitchAlertDialog(
        allowSelected = allowSelected,
        onConfirm = {
          model.showSwitchDialog.set(false)
          model.performSelectionSwitch()
        },
        onDismiss = { model.showSwitchDialog.set(false) },
    )
  }

  Scaffold(
      topBar = {
        Header(
            titleRes = R.string.split_tunneling,
            onBack = backToSettings,
            actions = {
              Row {
                FusMenu(viewModel = model, onSwitchClick = { model.showSwitchDialog.set(true) })
                IconButton(onClick = { model.showHeaderMenu.set(!showHeaderMenu) }) {
                  Icon(Icons.Default.MoreVert, "menu")
                }
              }
            },
        )
      },
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      if (mdmExcludedPackages.value?.isNotEmpty() == true) {
        item("mdmExcludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.certain_apps_are_not_routed_via_tailscale))
              }
          )
        }
      } else if (mdmIncludedPackages.value?.isNotEmpty() == true) {
        item("mdmIncludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.only_specific_apps_are_routed_via_tailscale))
              }
          )
        }
      } else {
        item("header") {
          ListItem(
              headlineContent = {
                Text(
                    stringResource(
                        if (allowSelected) R.string.selected_apps_will_access_tailscale
                        else
                            R.string
                                .selected_apps_will_access_the_internet_directly_without_using_tailscale
                    )
                )
              }
          )
        }
        item("search") {
          OutlinedTextField(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              value = searchQuery,
              onValueChange = { model.searchQuery.set(it) },
              singleLine = true,
              shape = MaterialTheme.shapes.extraLarge,
              colors = MaterialTheme.colorScheme.searchBarColors,
              leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
              placeholder = { Text(stringResource(R.string.search_apps)) },
          )
        }
        item("resolversHeader") {
          Lists.SectionDivider(
              stringResource(
                  if (allowSelected) R.string.count_included_apps else R.string.count_excluded_apps,
                  selectedPackageNames.count(),
              )
          )
        }
        if (needsAppListPermission) {
          item("appListPermission") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_list_permission_needed)) },
                supportingContent = {
                  Row {
                    TextButton(
                        onClick = {
                          permissionLauncher.launch(
                              InstalledAppsManager.GET_INSTALLED_APPS_PERMISSION
                          )
                        }
                    ) {
                      Text(stringResource(R.string.grant_permission))
                    }
                    TextButton(
                        onClick = {
                          context.startActivity(
                              Intent(
                                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                  Uri.fromParts("package", context.packageName, null),
                              )
                          )
                        }
                    ) {
                      Text(stringResource(R.string.open_app_settings))
                    }
                  }
                },
            )
          }
        }
        if (installedApps?.isEmpty() == true && !needsAppListPermission) {
          item("noApps") {
            ListItem(headlineContent = { Text(stringResource(R.string.no_results)) })
          }
        }
        if (installedApps == null) {
          item("spinner") {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(
                  modifier = Modifier.width(64.dp),
                  color = MaterialTheme.colorScheme.secondary,
                  trackColor = MaterialTheme.colorScheme.surfaceVariant,
              )
            }
          }
        } else {
          items(visibleApps, key = { it.packageName }) { app ->
            val icon by
                produceState<ImageBitmap?>(
                    model.cachedIcon(app.packageName),
                    app.packageName,
                    iconSizePx,
                ) {
                  value = model.loadIcon(app.packageName, iconSizePx)
                }

            ListItem(
                headlineContent = { Text(app.name, fontWeight = FontWeight.SemiBold) },
                leadingContent = {
                  val bitmap = icon
                  if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                    )
                  } else {
                    Spacer(modifier = Modifier.size(iconSize))
                  }
                },
                supportingContent = {
                  Text(
                      app.packageName,
                      color = MaterialTheme.colorScheme.secondary,
                      fontSize = MaterialTheme.typography.bodySmall.fontSize,
                      letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing,
                  )
                },
                trailingContent = {
                  Checkbox(
                      checked = selectedPackageNames.contains(app.packageName),
                      enabled = !builtInDisallowedPackageNames.contains(app.packageName),
                      onCheckedChange = { checked ->
                        if (checked) {
                          model.select(packageName = app.packageName)
                        } else {
                          model.deselect(packageName = app.packageName)
                        }
                      },
                  )
                },
            )
            Lists.ItemDivider()
          }
        }
      }
    }
  }
}

@Composable
fun FusMenu(viewModel: SplitTunnelAppPickerViewModel, onSwitchClick: (() -> Unit)) {
  val expanded by viewModel.showHeaderMenu.collectAsState()
  val allowSelected by viewModel.allowSelected.collectAsState()
  val showSystemApps by viewModel.showSystemApps.collectAsState()

  DropdownMenu(
      expanded = expanded,
      onDismissRequest = { viewModel.showHeaderMenu.set(false) },
      modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
  ) {
    MenuItem(
        onClick = {
          viewModel.showHeaderMenu.set(false)
          onSwitchClick()
        },
        text =
            stringResource(
                if (allowSelected) R.string.switch_to_select_to_exclude
                else R.string.switch_to_select_to_include
            ),
    )
    MenuItem(
        onClick = {
          viewModel.showHeaderMenu.set(false)
          viewModel.showSystemApps.set(!showSystemApps)
        },
        text =
            stringResource(
                if (showSystemApps) R.string.hide_system_apps else R.string.show_system_apps
            ),
    )
  }
}

@Composable
fun SwitchAlertDialog(allowSelected: Boolean, onConfirm: (() -> Unit), onDismiss: (() -> Unit)) {
  val switchString =
      stringResource(
          if (allowSelected) R.string.switch_to_select_to_exclude
          else R.string.switch_to_select_to_include
      )
  val switchDescription =
      stringResource(
          if (allowSelected)
              R.string.selected_apps_will_access_the_internet_directly_without_using_tailscale
          else R.string.selected_apps_will_access_tailscale
      )

  AlertDialog(
      title = { Text(text = "$switchString?") },
      text = {
        Text(
            text =
                stringResource(R.string.your_current_selection_will_be_cleared) +
                    "\n$switchDescription"
        )
      },
      onDismissRequest = onDismiss,
      confirmButton = { TextButton(onClick = onConfirm) { Text(text = switchString) } },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
      },
  )
}
