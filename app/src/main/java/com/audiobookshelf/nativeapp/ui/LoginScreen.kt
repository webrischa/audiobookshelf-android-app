package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.audiobookshelf.nativeapp.AbsViewModel

private val Protocols = listOf("https", "http")
private val SchemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * Hand-drawn dropdown caret. It is rendered as an overlay on top of the field instead of being
 * passed to OutlinedTextField's trailingIcon slot: that slot reserves a fixed minimum width
 * regardless of the icon's own size, which clipped the trailing "s" in "https" at 96dp. An
 * overlay reserves no layout width at all, so the whole field is available to the text.
 */
@Composable
private fun DropdownCaret(expanded: Boolean, modifier: Modifier = Modifier) {
  val tint = MaterialTheme.colorScheme.onSurfaceVariant
  Canvas(
    modifier = modifier
      // A floating label pushes the text's visual centre ~7dp below the field centre;
      // nudge the overlay down so the caret lines up with "https".
      .offset(y = 7.dp)
      .size(width = 12.dp, height = 7.dp)
      .graphicsLayer { rotationX = if (expanded) 180f else 0f }
  ) {
    drawPath(
      path = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width / 2f, size.height)
        close()
      },
      color = tint
    )
  }
}

/**
 * Joins the protocol select and the host field into a full server url.
 * A scheme pasted into the host field is stripped so "https://x" + "http" cannot end up as
 * "http://https://x".
 */
fun buildServerUrl(protocol: String, host: String): String {
  val cleanHost = host.trim().replace(SchemeRegex, "").trimEnd('/')
  if (cleanHost.isEmpty()) return ""
  return "${protocol.lowercase()}://$cleanHost"
}

/**
 * Splits a stored server url back into protocol + host, used to prefill the form.
 *
 * The remote address defaults to https and the LAN address to http: a self hosted server on the
 * local network is usually plain http, while the public one is expected to be behind TLS.
 */
fun splitServerUrl(serverUrl: String?, defaultProtocol: String = "https"): Pair<String, String> {
  if (serverUrl.isNullOrBlank()) return defaultProtocol to ""
  val match = SchemeRegex.find(serverUrl.trim())
  val protocol = match?.value?.removeSuffix("://")?.lowercase() ?: defaultProtocol
  val host = if (match != null) serverUrl.trim().substring(match.value.length) else serverUrl.trim()
  return protocol to host.trimEnd('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: AbsViewModel, onLoggedIn: () -> Unit) {
  // A live session wins; after "switch user" the addresses come from the values kept by the view
  // model instead, since the session itself is already gone at that point.
  val initial = remember { splitServerUrl(vm.session?.serverUrl ?: vm.loginServerUrl) }
  var protocol by rememberSaveable { mutableStateOf(initial.first) }
  var protocolExpanded by remember { mutableStateOf(false) }
  var host by rememberSaveable { mutableStateOf(initial.second) }
  // The lan address keeps its own protocol, independent from the remote one
  val localInitial = remember {
    splitServerUrl(vm.session?.localServerUrl ?: vm.loginLocalServerUrl, defaultProtocol = "http")
  }
  var localProtocol by rememberSaveable { mutableStateOf(localInitial.first) }
  var localProtocolExpanded by remember { mutableStateOf(false) }
  var localHost by rememberSaveable { mutableStateOf(localInitial.second) }
  var username by rememberSaveable { mutableStateOf("") }
  // Deliberately not rememberSaveable: that writes the value into the saved instance state bundle,
  // which the system persists to disk and hands to another process on restore. A password does not
  // belong there — the form survives rotation either way, the user just retypes it after the
  // process was killed.
  var password by remember { mutableStateOf("") }

  val serverUrl = buildServerUrl(protocol, host)
  val localServerUrl = if (localHost.isBlank()) "" else buildServerUrl(localProtocol, localHost)
  // The remote address is optional: a LAN-only setup can leave it empty, and the LAN address is
  // then used for the session as well.
  val effectiveServerUrl = serverUrl.ifBlank { localServerUrl }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.Start
  ) {
    Text("Audiobookshelf", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(28.dp))

    GlassCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
          ExposedDropdownMenuBox(
        expanded = protocolExpanded,
        onExpandedChange = { protocolExpanded = !protocolExpanded },
        // 96dp is fixed. The caret is drawn as an overlay (see DropdownCaret) so that
        // OutlinedTextField reserves no trailing-icon slot and "https" fits in full.
        modifier = Modifier.width(96.dp)
      ) {
        Box(Modifier.width(96.dp)) {
          OutlinedTextField(
            value = protocol,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("协议") },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
          )
          DropdownCaret(
            expanded = protocolExpanded,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
          )
        }
        ExposedDropdownMenu(
          expanded = protocolExpanded,
          onDismissRequest = { protocolExpanded = false }
        ) {
          Protocols.forEach { option ->
            DropdownMenuItem(
              text = { Text(option) },
              onClick = {
                protocol = option
                protocolExpanded = false
              }
            )
          }
        }
      }
      Spacer(Modifier.width(8.dp))
      OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("服务器地址") },
        placeholder = { Text("domain[:port]/ip:port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.weight(1f)
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      text = when {
        serverUrl.isNotBlank() -> serverUrl
        localServerUrl.isNotBlank() -> "未填服务器地址，将使用局域网地址登录"
        else -> "${protocol}://…"
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Top) {
      ExposedDropdownMenuBox(
        expanded = localProtocolExpanded,
        onExpandedChange = { localProtocolExpanded = !localProtocolExpanded },
        modifier = Modifier.width(96.dp)
      ) {
        Box(Modifier.width(96.dp)) {
          OutlinedTextField(
            value = localProtocol,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("协议") },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
          )
          DropdownCaret(
            expanded = localProtocolExpanded,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
          )
        }
        ExposedDropdownMenu(
          expanded = localProtocolExpanded,
          onDismissRequest = { localProtocolExpanded = false }
        ) {
          Protocols.forEach { option ->
            DropdownMenuItem(
              text = { Text(option) },
              onClick = {
                localProtocol = option
                localProtocolExpanded = false
              }
            )
          }
        }
      }
      Spacer(Modifier.width(8.dp))
      OutlinedTextField(
        value = localHost,
        onValueChange = { localHost = it },
        label = { Text("局域网地址（可选）") },
        placeholder = { Text("ip:port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.weight(1f)
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      text = localServerUrl.ifBlank { "${localProtocol}://…" },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = "连 Wi-Fi 时优先用局域网地址登录和播放，连不上自动回退到服务器地址",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
      value = username,
      onValueChange = { username = it },
      label = { Text("用户名") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("密码") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    ErrorBanner(vm.error) { vm.clearError() }

    Spacer(Modifier.height(16.dp))
    Button(
      onClick = { vm.login(effectiveServerUrl, localServerUrl.ifBlank { null }, username, password, onLoggedIn) },
      enabled = !vm.loading && effectiveServerUrl.isNotBlank() && username.isNotBlank(),
      modifier = Modifier.fillMaxWidth()
    ) {
      if (vm.loading) {
        // Constrain both dimensions. M3 draws the arc from the canvas *width*, so a height-only
        // modifier leaves a 40dp-wide / 20dp-tall canvas and the ring spills above and below the
        // button instead of shrinking to a 20dp circle.
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      } else {
        Text("连接")
      }
    }
  }
}
