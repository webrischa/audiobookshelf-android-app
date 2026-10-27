package com.audiobookshelf.nativeapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.audiobookshelf.nativeapp.AbsViewModel

private val SkipSecondPresets = listOf(10, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AbsViewModel, onBack: () -> Unit, onLoggedOut: () -> Unit) {
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) vm.setBackgroundImageFromUri(uri)
  }

  val session = vm.session
  val activeUrl = vm.activeServerUrl
  val remoteActive = activeUrl != null && activeUrl == session?.serverUrl
  val localUrl = session?.localServerUrl
  val localActive = !localUrl.isNullOrBlank() && activeUrl == localUrl
  // Manual switching only makes sense once an address is in use and there is another one to go to
  val canSwitch = activeUrl != null

  var skipSheetOpen by remember { mutableStateOf(false) }

  // The floating playback bar is only on screen while something is loaded; its inset doubles as the
  // presence flag, because the nav layer publishes exactly the bar's height and nothing else
  val miniPlayerInset = LocalMiniPlayerBottomInset.current
  val playbackBarVisible = miniPlayerInset > 0.dp

  Box(Modifier.fillMaxSize()) {
    // A private recording of this screen, so the sheet can sample real pixels without the
    // navigation layer ending up referencing the panel that draws it.
    val sheetBackdrop = rememberLayerBackdrop()
    // Only while the sheet is open: layerBackdrop re-records the page every frame and nothing else
    // on this screen reads it. The shared helper also keeps the layer alive through the exit
    // animation, so a closing panel never samples a layer that is already gone.
    val recording = rememberBackdropRecording(skipSheetOpen)
    Box(
      Modifier
        .fillMaxSize()
        .then(if (recording) Modifier.layerBackdrop(sheetBackdrop) else Modifier)
    ) {
      Scaffold(
        containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
          GlassBar {
            ScreenHeader(title = "设置", onBack = onBack)
          }
        }
      ) { padding ->
        // Scrolls: on a short screen the last card used to be cut off with no way to reach it
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(top = 2.dp, bottom = 12.dp + miniPlayerInset)
        ) {
          GlassCard(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp)
          ) {
            val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
              headlineContent = { Text("服务器") },
              supportingContent = {
                Text(
                  text = session?.serverUrl ?: "-",
                  color = if (remoteActive) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  }
                )
              },
              trailingContent = {
                // The row in use needs no button: it is already the active address
                if (canSwitch && !remoteActive && !session?.serverUrl.isNullOrBlank()) {
                  TextButton(onClick = { vm.switchToServer(session?.serverUrl.orEmpty()) }) {
                    Text("切换")
                  }
                }
              },
              colors = rowColors
            )
            ListItem(
              headlineContent = { Text("局域网地址") },
              supportingContent = {
                Text(
                  text = session?.localServerUrl ?: "未设置",
                  color = if (localActive) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  }
                )
              },
              trailingContent = {
                if (canSwitch && !localActive && !localUrl.isNullOrBlank()) {
                  TextButton(onClick = { vm.switchToServer(localUrl) }) {
                    Text("切换")
                  }
                }
              },
              colors = rowColors
            )
            ListItem(
              headlineContent = { Text("用户") },
              supportingContent = { Text(session?.username ?: "-") },
              trailingContent = {
                // Same teardown as logging out, only the addresses are kept for the next account
                Row(verticalAlignment = Alignment.CenterVertically) {
                  TextButton(onClick = { vm.switchUser(onLoggedOut) }) {
                    Text("切换")
                  }
                  TextButton(onClick = { vm.logout(onLoggedOut) }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                  }
                }
              },
              colors = rowColors
            )
          }

          GlassCard(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 6.dp)
          ) {
            val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
              headlineContent = { Text("耳机控制") },
              supportingContent = { Text("摘下耳机自动暂停，重新戴上继续播放") },
              trailingContent = {
                GlassSwitch(
                  checked = vm.headsetControlEnabled,
                  onCheckedChange = { vm.updateHeadsetControl(it) }
                )
              },
              colors = rowColors
            )
            ListItem(
              headlineContent = { Text("提示音不打断") },
              supportingContent = { Text("短提示音响起时不暂停；微信等语音消息暂停并在结束后自动续播") },
              trailingContent = {
                GlassSwitch(
                  checked = vm.ignoreNotificationSounds,
                  onCheckedChange = { vm.updateIgnoreNotificationSounds(it) }
                )
              },
              colors = rowColors
            )
            ListItem(
              headlineContent = { Text("打断后续播") },
              supportingContent = { Text("来电、闹钟等应用抢占后自动继续播放") },
              trailingContent = {
                GlassSwitch(
                  checked = vm.autoResumeEnabled,
                  onCheckedChange = { vm.updateAutoResume(it) }
                )
              },
              colors = rowColors
            )
            ListItem(
              headlineContent = { Text("快进 / 快退") },
              supportingContent = { Text("长按播放页的按钮可连续跳转") },
              trailingContent = {
                TextButton(onClick = { skipSheetOpen = true }) {
                  Text("${vm.skipSeconds} 秒", color = MaterialTheme.colorScheme.primary)
                }
              },
              colors = rowColors
            )
          }

          GlassCard(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 6.dp)
          ) {
            val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
              headlineContent = { Text("背景") },
              supportingContent = { Text("更换背景图") },
              trailingContent = {
                Row {
                  TextButton(
                    onClick = {
                      picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                      )
                    }
                  ) { Text("更换") }
                  TextButton(onClick = { vm.restoreDefaultBackground() }) { Text("恢复默认") }
                }
              },
              colors = rowColors
            )
          }
        }
      }

    }

    SkipSecondsSheet(
      backdrop = sheetBackdrop,
      visible = skipSheetOpen,
      seconds = vm.skipSeconds,
      bottomInset = if (playbackBarVisible) miniPlayerInset else 0.dp,
      bottomGap = if (playbackBarVisible) 10.dp else 0.dp,
      onSelect = { vm.updateSkipSeconds(it) },
      onDismiss = { skipSheetOpen = false }
    )

    // A refused manual switch is a dialog, not the shared error banner: the banner sits on the
    // page until some later operation clears it, which read as a message that never goes away.
    vm.switchError?.let { message ->
      AlertDialog(
        onDismissRequest = { vm.clearSwitchError() },
        title = { Text("切换失败") },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { vm.clearSwitchError() }) { Text("确定") }
        }
      )
    }
  }
}

/** Bottom sheet for the quick skip length; same shape as the player's speed sheet. */
@Composable
private fun SkipSecondsSheet(
  backdrop: Backdrop,
  visible: Boolean,
  seconds: Int,
  bottomInset: Dp,
  bottomGap: Dp,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  GlassSheet(
    backdrop = backdrop,
    visible = visible,
    onDismiss = onDismiss,
    bottomInset = bottomInset,
    bottomGap = bottomGap
  ) {
    SheetHeader("快进 / 快退") { onDismiss() }
    SheetCard {
      Text(
        text = "$seconds 秒",
        color = Color.White,
        fontSize = 40.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(Alignment.CenterHorizontally)
      )
      Spacer(Modifier.height(20.dp))
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
      ) {
        SkipSecondPresets.forEach { option ->
          GlassPresetCircle(
            label = "$option",
            selected = seconds == option,
            onClick = { onSelect(option) }
          )
        }
      }
    }
    Spacer(Modifier.height(14.dp))
    SheetHint("长按播放页的按钮可连续跳转，设置仅保存在本机")
  }
}