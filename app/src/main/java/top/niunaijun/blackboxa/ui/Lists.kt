package top.niunaijun.blackboxa.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Pill-shaped search field used above app lists. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, null) }
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** One app row: icon, label, package name, optional trailing control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    icon: Drawable?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val m = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else Modifier.clickable(onClick = onClick)
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle == null && supporting == null) null else {
            {
                androidx.compose.foundation.layout.Column {
                    if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (supporting != null) Text(
                        supporting, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        leadingContent = { DrawableIcon(icon, 40.dp) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = m
    )
}

