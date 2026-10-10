package app.vaulttasks.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.vaulttasks.ui.components.LocalCategoryColors
import app.vaulttasks.ui.components.onCategoryColor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Drag the ≡ handles to put the categories in the order they should appear in the grouped list. */
@Composable
fun CategoryOrderDialog(
    categories: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf(categories) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(indexOf(to.key as String), removeAt(indexOf(from.key as String))) }
    }
    val colors = LocalCategoryColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder categories") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items, key = { _, c -> c }) { _, c ->
                    ReorderableItem(reorder, key = c) { dragging ->
                        val picked = colors[c]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (picked != null) {
                                Text(
                                    "#$c", color = onCategoryColor(picked), style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color(picked)).padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            } else {
                                Text("#$c", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(
                                "≡", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 16.dp).draggableHandle(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(items) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
