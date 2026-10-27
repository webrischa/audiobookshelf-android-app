package com.audiobookshelf.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.audiobookshelf.nativeapp.AbsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
  vm: AbsViewModel,
  libraryId: String,
  onOpenItem: (String) -> Unit,
  onBack: () -> Unit
) {
  LaunchedEffect(libraryId) { vm.clearSearch() }

  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Scaffold(
    containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    topBar = {
      GlassBar {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
          OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.onSearchQueryChanged(libraryId, it) },
            placeholder = { Text("搜索书名、作者") },
            singleLine = true,
            // Magnifier sits on the trailing side. The clear button gets a fixed slot so the
            // magnifier never shifts when text appears or disappears.
            trailingIcon = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
              ) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                  if (vm.searchQuery.isNotEmpty()) {
                    Icon(
                      Icons.Default.Close,
                      contentDescription = "清除",
                      modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { vm.onSearchQueryChanged(libraryId, "") }
                        .padding(9.dp)
                    )
                  }
                }
                Icon(
                  Icons.Default.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurface
                )
              }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            // No outline at all: the field sits directly on the glass bar
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Color.Transparent,
              unfocusedBorderColor = Color.Transparent,
              disabledBorderColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f).focusRequester(focusRequester)
          )
          Spacer(Modifier.width(4.dp))
        }
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      ErrorBanner(vm.error) { vm.clearError() }

      when {
        vm.searching && vm.searchResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
        // Hints sit right under the query field: centred text in an empty page reads as a
        // different screen rather than as the state of this one
        vm.searchQuery.isBlank() -> EmptyState(
          text = "输入关键词开始搜索",
          alignment = Alignment.TopCenter,
          modifier = Modifier.padding(top = 48.dp)
        )
        vm.searchResults.isEmpty() -> EmptyState(
          text = "没有找到匹配的内容",
          alignment = Alignment.TopCenter,
          modifier = Modifier.padding(top = 48.dp)
        )
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = LocalMiniPlayerBottomInset.current)
        ) {
          items(vm.searchResults, key = { it.id }) { item ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .glassSurface()
                .clickable {
                  keyboard?.hide()
                  onOpenItem(item.id)
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              BookCover(
                url = vm.coverUrl(item.id),
                hasCover = item.hasCover(),
                modifier = Modifier.size(52.dp)
              )
              Spacer(Modifier.width(12.dp))
              Column(verticalArrangement = Arrangement.Center) {
                Text(
                  text = item.media.metadata.title ?: "未命名",
                  style = MaterialTheme.typography.bodyLarge,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = item.media.metadata.authorName ?: "",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      }
    }
  }
}
