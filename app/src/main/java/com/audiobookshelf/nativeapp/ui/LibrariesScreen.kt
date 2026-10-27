package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.audiobookshelf.nativeapp.AbsViewModel
import com.audiobookshelf.nativeapp.data.LibraryDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
  vm: AbsViewModel,
  onOpenLibrary: (LibraryDto) -> Unit,
  onBack: (() -> Unit)?,
  onSettings: () -> Unit
) {
  LaunchedEffect(Unit) {
    if (vm.libraries.isEmpty()) vm.loadLibraries()
  }

  Scaffold(
    containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground,
    topBar = {
      GlassBar {
        ScreenHeader(
          title = "媒体库",
          onBack = onBack,
          actions = {
            IconButton(onClick = onSettings) {
              Icon(Icons.Default.Settings, contentDescription = "设置")
            }
          }
        )
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      ErrorBanner(vm.error) { vm.clearError() }

      when {
        vm.loading && vm.libraries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
        vm.libraries.isEmpty() -> Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = if (vm.error != null) "媒体库加载失败" else "没有可用媒体库",
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (vm.error != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.loadLibraries() }) { Text("重试") }
          }
        }
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current)
        ) {
          items(vm.libraries, key = { it.id }) { library ->
            GlassCard(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .clickable { onOpenLibrary(library) }
            ) {
              ListItem(
                headlineContent = { Text(library.name) },
                supportingContent = { Text(if (library.mediaType == "podcast") "播客" else "有声书") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
              )
            }
          }
        }
      }
    }
  }
}
