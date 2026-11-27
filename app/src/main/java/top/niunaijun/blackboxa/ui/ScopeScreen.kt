package top.niunaijun.blackboxa.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.niunaijun.blackboxa.view.xposed.VirtualApp

/** App list with checkboxes, used for Xposed module scope and GPU driver assignment. */
@Composable
fun ScopeScreen(
    title: String,
    subtitle: String?,
    hint: String?,
    apps: List<VirtualApp>?,
    isSelected: (String) -> Boolean,
    onToggle: (String) -> Unit,
    menu: List<Pair<String, () -> Unit>>,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    AppScaffold(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            if (menu.isNotEmpty()) Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            SearchField(query, { query = it }, androidx.compose.ui.res.stringResource(top.niunaijun.blackboxa.R.string.search_apps))
            when {
                apps == null -> CenterProgress()
                apps.isEmpty() -> EmptyState(androidx.compose.ui.res.stringResource(top.niunaijun.blackboxa.R.string.empty_empty))
                else -> LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                    items(apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) },
                        key = { it.packageName }) { app ->
                        AppRow(
                            icon = app.icon,
                            title = app.label,
                            subtitle = app.packageName,
                            onClick = { onToggle(app.packageName) },
                            trailing = { Checkbox(checked = isSelected(app.packageName), onCheckedChange = { onToggle(app.packageName) }) }
                        )
                    }
                }
            }
        }
    }
}
