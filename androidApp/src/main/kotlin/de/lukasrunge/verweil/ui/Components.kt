package de.lukasrunge.verweil.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.timeline.PlaceTag

/** A screen below the main one: title, back arrow, scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

/** Heading of a group of rows. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A row in a settings-like list: icon, title, optional detail, optional trailing control. */
@Composable
fun ListRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = if (titleColor == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else titleColor,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(@DrawableRes icon: Int, title: String, detail: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListRow(
        icon = icon,
        title = title,
        detail = detail,
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

enum class Tone { Warning, Error, Info }

/** Something the user should fix: what is wrong, why it matters, and the one action that fixes it. */
@Composable
fun Notice(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.Error -> colors.errorContainer to colors.onErrorContainer
        Tone.Warning -> colors.secondaryContainer to colors.onSecondaryContainer
        Tone.Info -> colors.surfaceContainerHigh to colors.onSurface
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = if (action == null) 16.dp else 6.dp)) {
            Icon(ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                if (action != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text(action, color = content)
                        }
                    }
                }
            }
        }
    }
}

/** Asks before something that cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A place's name with its Dawarich tags after it, each as a small label in the tag's own colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceName(name: String, tags: List<PlaceTag>, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    if (tags.isEmpty()) {
        Text(name, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        tags.forEach { TagLabel(it) }
    }
}

/**
 * Tinted with the tag's colour, the text in that colour; moved towards the theme's text colour only as far as it
 * takes to read well on the tint, which on a light background darkens a bright colour and on a dark one changes nothing.
 */
@Composable
private fun TagLabel(tag: PlaceTag) {
    val color = tag.color?.toColorOrNull() ?: MaterialTheme.colorScheme.secondary
    val tint = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.background)
    Text(
        listOfNotNull(tag.icon, "#${tag.name}").joinToString(" "),
        style = MaterialTheme.typography.labelMedium,
        color = readable(color, on = tint, towards = MaterialTheme.colorScheme.onBackground),
        maxLines = 1,
        modifier = Modifier
            .background(tint, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** [color], blended towards [towards] in small steps until its WCAG contrast with [on] is at least 4.5. */
private fun readable(color: Color, on: Color, towards: Color): Color {
    fun contrast(a: Color) = (maxOf(a.luminance(), on.luminance()) + 0.05) / (minOf(a.luminance(), on.luminance()) + 0.05)
    var step = 0
    var result = color
    while (contrast(result) < 4.5 && step < 10) {
        step++
        result = lerp(color, towards, step / 10f)
    }
    return result
}

/** "#FF5733" or "#F53" as set in Dawarich; null for anything else. */
private fun String.toColorOrNull(): Color? {
    val hex = removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
    if (hex.length != 6) return null
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}
