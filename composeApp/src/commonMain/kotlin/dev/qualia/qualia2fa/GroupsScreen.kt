package dev.qualia.qualia2fa

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_close
import dev.qualia.qualia2fa.resources.action_move_down
import dev.qualia.qualia2fa.resources.action_move_to
import dev.qualia.qualia2fa.resources.action_move_up
import dev.qualia.qualia2fa.resources.action_more
import dev.qualia.qualia2fa.resources.action_new_group
import dev.qualia.qualia2fa.resources.action_remove
import dev.qualia.qualia2fa.resources.action_rename
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.dialog_new_group
import dev.qualia.qualia2fa.resources.field_group_name
import dev.qualia.qualia2fa.resources.groups_depth_limit
import dev.qualia.qualia2fa.resources.groups_drag_hint
import dev.qualia.qualia2fa.resources.groups_invalid_move
import dev.qualia.qualia2fa.resources.groups_move_under
import dev.qualia.qualia2fa.resources.groups_name_taken
import dev.qualia.qualia2fa.resources.groups_order_hint
import dev.qualia.qualia2fa.resources.groups_remove_hint
import dev.qualia.qualia2fa.resources.groups_subgroup
import dev.qualia.qualia2fa.resources.groups_top
import dev.qualia.qualia2fa.resources.icon_chevron_right
import dev.qualia.qualia2fa.resources.icon_expand_more
import dev.qualia.qualia2fa.resources.icon_more
import dev.qualia.qualia2fa.resources.settings_groups
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** One visible row of the group tree, already flattened into display order. */
private data class TreeRow(
    val ref: String,
    val depth: Int,
    val parentId: String?,
    val index: Int,
    val last: Boolean,
    /** For each ancestor level, whether that ancestor has a following sibling. */
    val ancestorsContinue: List<Boolean>,
    val group: Group?,
    val entry: Entry?,
    val root: Boolean = false,
)

private val TreeRowHeight = 48.dp
private val TreeIndent = 22.dp
private val TreeConnectorWidth = 22.dp

/**
 * The groups as a tree of groups and entries.
 *
 * The tree is part of the vault since format version 4, so every change here is written into the
 * vault: there is no arrangement stored beside the preferences to keep in step. The depth rule
 * is enforced by [GroupLayout] and by the vault itself; the two agree, so a move the screen allows
 * is a move that stores.
 *
 * Rows can be dragged after a long press. Dropping on a group appends the dragged node to that
 * group's children; dropping on an entry inserts before or after it in the entry's parent. The
 * menus provide the same operations for a single step and for parents that would otherwise be
 * hard to reach.
 */
@Composable
fun GroupsScreen(
    vault: Vault,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var tree by remember(vault) { mutableStateOf(GroupLayout.fromVault(vault)) }
    var groups by remember(vault) { mutableStateOf(vault.groups()) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var creatingParent by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var removing by remember { mutableStateOf<Group?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dragRef by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dropIndex by remember { mutableIntStateOf(-1) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { TreeRowHeight.toPx() }

    val nameTaken = stringResource(Res.string.groups_name_taken)
    val invalidMove = stringResource(Res.string.groups_invalid_move)
    val top =
        groups.firstOrNull { it.top }
            ?: return
    val entries = vault.entries()
    val entriesById = entries.associateBy { it.id }
    val groupsById = groups.associateBy { it.id }
    val rows =
        buildRows(
            tree = tree,
            top = top,
            groupsById = groupsById,
            entriesById = entriesById,
            collapsed = collapsed,
        )

    /**
     * Writes [next] into the vault and reloads the tree from what the vault accepted.
     *
     * The reload is not a formality: the vault is the truth, and a write that it refused leaves
     * the view showing something the vault does not hold until the next read.
     */
    fun publish(next: GroupLayout) {
        if (!next.applyTo(vault)) {
            message = invalidMove
        }
        tree = GroupLayout.fromVault(vault)
        groups = vault.groups()
        onChanged()
    }

    fun siblings(ref: String): Pair<String, Int>? {
        val parent = tree.parentOf(ref) ?: return null
        val list = tree.childrenOf(parent)
        val index = list.indexOf(ref)
        return if (index < 0) null else parent to index
    }

    fun moveWithin(ref: String, step: Int) {
        val (parent, index) = siblings(ref) ?: return
        val target = index + step
        if (target < 0 || target >= tree.childrenOf(parent).size) return
        val next = tree.withMoved(ref, parent, target) ?: run {
            message = invalidMove
            return
        }
        publish(next)
    }

    fun moveToGroup(ref: String, parentId: String) {
        val source = tree.parentOf(ref) ?: return
        if (source == parentId) return
        val next =
            tree.withMoved(ref, parentId, tree.childrenOf(parentId).size) ?: run {
                message = invalidMove
                return
            }
        publish(next)
    }

    val sourceIndex = if (dragRef == null) -1 else rows.indexOfFirst { it.ref == dragRef }
    val ghostIndex =
        if (dragRef == null || rows.size <= 1) -1 else dropIndex.coerceIn(1, rows.lastIndex)

    fun drop(sourceRef: String, target: TreeRow) {
        if (target.root) return
        if (target.ref == sourceRef) return

        val parent = if (target.group != null) target.group.id else target.parentId ?: top.id
        val index =
            if (target.group != null) {
                tree.childrenOf(parent).size
            } else {
                // The row that was dropped on gives up its slot, which is exactly what the
                // ghost showed while dragging: the positions after the source is removed are
                // counted up to the target's original index.
                target.index
            }
        val next = tree.withMoved(sourceRef, parent, index) ?: run {
            message = invalidMove
            return
        }
        publish(next)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = Space.xl, top = Space.l, end = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        ScreenHeader(title = stringResource(Res.string.settings_groups)) {
            TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
        }
        Hint(stringResource(Res.string.groups_order_hint))
        Hint(stringResource(Res.string.groups_drag_hint))
        Hint(stringResource(Res.string.groups_depth_limit))

        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedButton(onClick = { creatingParent = top.id }) {
                Text(stringResource(Res.string.action_new_group))
            }
        }

        MessageLine(message)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // The ghost is the first child: the rows paint over it, so the lifted row stays
                // on top of its own landing marker while the gap around it shows the marker.
                if (ghostIndex in 1..rows.lastIndex && sourceIndex >= 0) {
                    DragGhost(
                        row = rows[sourceIndex],
                        target = rows[ghostIndex],
                        y = TreeRowHeight * ghostIndex,
                    )
                }
                Column {
                    rows.forEachIndexed { rowIndex, row ->
                        val dragging = dragRef == row.ref
                        val shiftTarget =
                            when {
                                dragRef == null || dragging || sourceIndex < 0 -> 0f
                                sourceIndex < dropIndex && rowIndex in (sourceIndex + 1)..dropIndex ->
                                    -rowHeightPx
                                sourceIndex > dropIndex && rowIndex in dropIndex until sourceIndex ->
                                    rowHeightPx
                                else -> 0f
                            }
                        val shift by
                            animateFloatAsState(
                                targetValue = shiftTarget,
                                animationSpec = tween(durationMillis = 140),
                                label = "group-row-shift",
                            )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(TreeRowHeight)
                                    // The dragged row floats above the list and follows the
                                    // finger; the ghost below it marks the slot it will take.
                                    .zIndex(if (dragging) 2f else 0f)
                                    .graphicsLayer { translationY = if (dragging) dragDelta else shift }
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (dragging) {
                                            MaterialTheme.colorScheme.surface
                                        } else {
                                            androidx.compose.ui.graphics.Color.Transparent
                                        },
                                    )
                                    .padding(horizontal = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .pointerInput(row.ref) {
                                    if (row.root) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragRef = row.ref
                                            dragDelta = 0f
                                            dragStartIndex = rowIndex
                                            dropIndex = rowIndex
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDelta += amount.y
                                            val shift = (dragDelta / rowHeightPx).roundToInt()
                                            dropIndex =
                                                if (rows.size <= 1) {
                                                    rowIndex
                                                } else {
                                                    (dragStartIndex + shift).coerceIn(1, rows.lastIndex)
                                                }
                                        },
                                        onDragEnd = {
                                            val target = rows.getOrNull(dropIndex) ?: row
                                            dragRef = null
                                            dragDelta = 0f
                                            dropIndex = -1
                                            drop(row.ref, target)
                                        },
                                        onDragCancel = {
                                            dragRef = null
                                            dragDelta = 0f
                                            dropIndex = -1
                                        },
                                    )
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.root) {
                            Text(
                                stringResource(Res.string.groups_top),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            TreeConnector(
                                depth = row.depth,
                                ancestorsContinue = row.ancestorsContinue,
                                last = row.last,
                                armExtension = if (row.group != null) 0.dp else 20.dp,
                            )
                            if (row.group != null) {
                                IconButton(
                                    onClick = {
                                        collapsed =
                                            if (row.group.id in collapsed) {
                                                collapsed - row.group.id
                                            } else {
                                                collapsed + row.group.id
                                            }
                                    },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (row.group.id in collapsed) {
                                                    Res.drawable.icon_chevron_right
                                                } else {
                                                    Res.drawable.icon_expand_more
                                                },
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                // The connector already spans the chevron slot for a row that
                                // has none, so only a hair of space is left before the label.
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = row.group?.name ?: row.entry?.presentedName().orEmpty(),
                                style =
                                    if (row.group != null) {
                                        MaterialTheme.typography.titleSmall
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                fontWeight = if (row.group != null) FontWeight.SemiBold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    if (row.root) {
                        // The toolbar already offers "New group"; the root row stays a heading.
                    } else if (row.group != null) {
                        TreeMenu(
                            row = row,
                            tree = tree,
                            expanded = menuFor == row.ref,
                            children = tree.childrenOf(row.group.id),
                            onOpen = { menuFor = row.ref },
                            onDismiss = { menuFor = null },
                            onNewSubgroup = {
                                menuFor = null
                                creatingParent = row.group.id
                            },
                            onRename = {
                                menuFor = null
                                renaming = row.group
                            },
                            onMove = {
                                menuFor = null
                                moving = row.ref
                            },
                            onMoveUp = {
                                menuFor = null
                                moveWithin(row.ref, -1)
                            },
                            onMoveDown = {
                                menuFor = null
                                moveWithin(row.ref, 1)
                            },
                            onRemove = {
                                menuFor = null
                                removing = row.group
                            },
                        )
                    } else if (row.entry != null) {
                        TreeMenu(
                            row = row,
                            tree = tree,
                            expanded = menuFor == row.ref,
                            children = emptyList(),
                            onOpen = { menuFor = row.ref },
                            onDismiss = { menuFor = null },
                            onNewSubgroup = null,
                            onRename = null,
                            onMove = {
                                menuFor = null
                                moving = row.ref
                            },
                            onMoveUp = {
                                menuFor = null
                                moveWithin(row.ref, -1)
                            },
                            onMoveDown = {
                                menuFor = null
                                moveWithin(row.ref, 1)
                            },
                            onRemove = null,
                        )
                    }
                }
            }
        }
    }
    }
    }

    creatingParent?.let { parent ->
        NameDialog(
            title =
                stringResource(
                    if (parent == top.id) Res.string.dialog_new_group else Res.string.groups_subgroup,
                ),
            initial = "",
            onDismiss = { creatingParent = null },
            onConfirm = { name ->
                creatingParent = null
                val groupId = vault.createGroupIn(parent, name)
                if (groupId == null) {
                    message = nameTaken
                } else {
                    val next =
                        tree.withNewGroup(groupId, parent, tree.childrenOf(parent).size)
                    if (next == null) {
                        message = invalidMove
                        onChanged()
                    } else {
                        publish(next)
                    }
                }
            },
        )
    }

    renaming?.let { group ->
        NameDialog(
            title = stringResource(Res.string.action_rename),
            initial = group.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (!vault.renameGroup(group.id, name)) {
                    message = nameTaken
                } else {
                    groups = vault.groups()
                    tree = GroupLayout.fromVault(vault)
                    onChanged()
                }
            },
        )
    }

    moving?.let { ref ->
        val excluded = if (isGroupRef(ref)) tree.groupSubtree(refId(ref)) else emptySet()
        val candidates =
            groups.filter { group ->
                group.id != top.id &&
                    group.id !in excluded &&
                    (isEntryRef(ref) || tree.depthOfGroup(group.id) < GroupLayout.MAX_GROUP_DEPTH)
            }
        AlertDialog(
            onDismissRequest = { moving = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.groups_move_under)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    TextButton(
                        onClick = {
                            moveToGroup(ref, top.id)
                            moving = null
                        },
                    ) { Text(stringResource(Res.string.groups_top)) }
                    candidates.forEach { group ->
                        TextButton(
                            onClick = {
                                moveToGroup(ref, group.id)
                                moving = null
                            },
                        ) { Text(group.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moving = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    removing?.let { group ->
        AlertDialog(
            onDismissRequest = { removing = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.action_remove)) },
            text = { Text(stringResource(Res.string.groups_remove_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        if (vault.removeGroup(group.id)) {
                            tree = GroupLayout.fromVault(vault)
                            groups = vault.groups()
                            onChanged()
                        } else {
                            message = nameTaken
                        }
                    },
                ) { Text(stringResource(Res.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/** The actions for one row, only the ones its kind and depth allow. */
@Composable
private fun TreeMenu(
    row: TreeRow,
    tree: GroupLayout,
    expanded: Boolean,
    children: List<String>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onNewSubgroup: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val group = row.group
    val canNest = group != null && tree.depthOfGroup(group.id) < GroupLayout.MAX_GROUP_DEPTH
    val siblingCount = row.parentId?.let { tree.childrenOf(it).size } ?: 1
    Box {
        IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(Res.drawable.icon_more),
                contentDescription = stringResource(Res.string.action_more),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (group != null && onNewSubgroup != null && canNest) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.groups_subgroup)) },
                    onClick = onNewSubgroup,
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_rename)) },
                    onClick = onRename,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_move_to)) },
                onClick = onMove,
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_move_up)) },
                onClick = onMoveUp,
                enabled = row.index > 0,
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_move_down)) },
                onClick = onMoveDown,
                enabled = row.index < siblingCount - 1,
            )
            if (onRemove != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_remove)) },
                    onClick = onRemove,
                )
            }
        }
    }
}

/**
 * The drawn tree guide for one row.
 *
 * These are lines, not box-drawing characters: the color and width come from the theme, so a
 * change of style changes the guide too. Ancestor guides are drawn for every level that still
 * has a following sibling; the row's own guide ends at its center when it is the last child.
 */
@Composable
private fun TreeConnector(
    depth: Int,
    ancestorsContinue: List<Boolean>,
    last: Boolean,
    /**
     * Extra width the horizontal arm spans before the row's content.
     *
     * A row with a chevron starts its content right after the guide, so the arm stops at the
     * chevron. A row without one leaves that slot empty; the arm crosses it so the guide still
     * points at the item instead of ending in the blank.
     */
    armExtension: Dp = 0.dp,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val width = TreeConnectorWidth + TreeIndent * (depth - 1).coerceAtLeast(0) + armExtension

    Canvas(modifier = Modifier.width(width).height(TreeRowHeight)) {
        val stroke = 1.dp.toPx()
        val centerY = size.height / 2f
        val indent = TreeIndent.toPx()
        val connector = TreeConnectorWidth.toPx()

        ancestorsContinue.forEachIndexed { level, continues ->
            if (!continues) return@forEachIndexed
            val x = level * indent + connector / 2f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke, StrokeCap.Round)
        }

        val x = (depth - 1) * indent + connector / 2f
        drawLine(color, Offset(x, 0f), Offset(x, if (last) centerY else size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(x, centerY), Offset(size.width, centerY), stroke, StrokeCap.Round)
    }
}

/**
 * The translucent landing place shown while a row is dragged.
 *
 * It is drawn over the gap the shifted rows leave, with a dashed outline and the dragged
 * row's label, so the drop position is visible before the finger is released.
 */
@Composable
private fun DragGhost(
    row: TreeRow,
    target: TreeRow,
    y: androidx.compose.ui.unit.Dp,
) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val outline = MaterialTheme.colorScheme.primary
    val name = row.group?.name ?: row.entry?.presentedName().orEmpty()
    // The same offsets the real row uses, so the ghost's label sits exactly over the label of
    // the row it will become: the tree guide, then the chevron slot.
    val start =
        TreeIndent * (target.depth - 1).coerceAtLeast(0) +
            TreeConnectorWidth +
            24.dp

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TreeRowHeight)
                .offset(y = y)
                .padding(horizontal = Space.s)
                .drawBehind {
                    val radius = CornerRadius(12.dp.toPx())
                    drawRoundRect(fill.copy(alpha = 0.18f), cornerRadius = radius)
                    drawRoundRect(
                        outline.copy(alpha = 0.55f),
                        cornerRadius = radius,
                        style =
                            Stroke(
                                width = 1.dp.toPx(),
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                                    ),
                            ),
                    )
                },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style =
                    if (row.group != null) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                fontWeight = if (row.group != null) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer { alpha = 0.55f },
            )
        }
    }
}

/** A dialog asking for one group name. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    val acceptable = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogDefaults,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.field_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = acceptable, onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/** The group and all groups below it. */
private fun GroupLayout.groupSubtree(sourceGroupId: String): Set<String> {
    val result = mutableSetOf<String>()
    fun visit(groupId: String) {
        if (!result.add(groupId)) return
        childrenOf(groupId).forEach { if (isGroupRef(it)) visit(refId(it)) }
    }
    visit(sourceGroupId)
    return result
}

/** Flattens the visible tree into the rows the screen draws. */
private fun buildRows(
    tree: GroupLayout,
    top: Group,
    groupsById: Map<String, Group>,
    entriesById: Map<String, Entry>,
    collapsed: Set<String>,
): List<TreeRow> {
    val result = mutableListOf<TreeRow>()
    result +=
        TreeRow(
            ref = groupRef(top.id),
            depth = 0,
            parentId = null,
            index = 0,
            last = true,
            ancestorsContinue = emptyList(),
            group = top,
            entry = null,
            root = true,
        )

    fun add(parentId: String, depth: Int, ancestors: List<Boolean>) {
        val refs = tree.childrenOf(parentId)
        refs.forEachIndexed { index, ref ->
            val last = index == refs.lastIndex
            if (isGroupRef(ref)) {
                val group = groupsById[refId(ref)] ?: return@forEachIndexed
                result +=
                    TreeRow(
                        ref = ref,
                        depth = depth,
                        parentId = parentId,
                        index = index,
                        last = last,
                        ancestorsContinue = ancestors,
                        group = group,
                        entry = null,
                    )
                if (group.id !in collapsed) add(group.id, depth + 1, ancestors + !last)
            } else {
                val entry = entriesById[refId(ref)] ?: return@forEachIndexed
                result +=
                    TreeRow(
                        ref = ref,
                        depth = depth,
                        parentId = parentId,
                        index = index,
                        last = last,
                        ancestorsContinue = ancestors,
                        group = null,
                        entry = entry,
                    )
            }
        }
    }
    add(top.id, 1, emptyList())
    return result
}
