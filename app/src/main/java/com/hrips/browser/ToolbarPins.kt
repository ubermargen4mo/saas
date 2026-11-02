package com.hrips.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class ToolbarPin(val id: String, val title: String, val icon: () -> ImageVector) {
    BOOKMARKS("bookmarks", "Закладки", { HripsIcons.Star.variant(2f, 0.95f) }),
    HISTORY("history", "История", { HripsIcons.TbHistory }),
    FIND("find", "Найти на странице", { HripsIcons.TbFind }),
    SETTINGS("settings", "Настройки", { HripsIcons.Settings.variant(2f, 0.95f) }),
}

@Composable
internal fun ToolbarPinMenu(expanded: Boolean, onDismiss: () -> Unit, pinned: List<String>, onToggle: (ToolbarPin) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = HripsShapes.ML) {
        ToolbarPin.entries.forEach { pin ->
            DropdownMenuItem(
                text = { Text(pin.title, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(pin.icon(), null, Modifier.size(22.dp)) },
                trailingIcon = if (pin.id in pinned) ({ Icon(HripsIcons.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }) else null,
                onClick = { onToggle(pin) },
            )
        }
    }
}
