package dev.qualia.qualia2fa

/**
 * The tree of groups and entries, with each parent's children in display order.
 *
 * # Why this is separate from the vault
 *
 * The vault stores groups as a flat list and an entry's group membership, which is what syncs
 * between devices. The nesting and the mixed order of entries and child groups are a view of
 * that flat data: the vault has no parent field and no place for a group between two entries.
 * The tree therefore lives beside the preferences, keyed by the vault's identifiers, and is
 * reconciled with the vault whenever the vault changes. An entry still belongs to exactly one
 * vault group, so moving it between groups there remains the operation that syncs; the tree
 * only decides where its row is drawn.
 *
 * A reference is `g:<group id>` or `e:<entry id>`. Both identifiers are hexadecimal, so the
 * serialized form needs no escaping beyond the `|` and `,` separators.
 *
 * # The depth limit
 *
 * The top group is depth zero. Named groups may be nested up to depth three; entries may sit
 * at any depth, including inside the deepest group. A group placed at depth four is rejected,
 * and a reconciled layout attaches such a group to the top instead of dropping it.
 */
class GroupLayout private constructor(
    val rootId: String,
    val children: Map<String, List<String>>,
) {
    /** The ordered children of [groupId], empty for an unknown group. */
    fun childrenOf(groupId: String): List<String> = children[groupId].orEmpty()

    /** The parent of [ref], or `null` when it is the root or unknown. */
    fun parentOf(ref: String): String? =
        children.entries.firstOrNull { (_, refs) -> ref in refs }?.key

    /** The depth of [groupId]; the root is zero. */
    fun depthOfGroup(groupId: String): Int {
        var depth = 0
        var current = groupId
        val seen = mutableSetOf<String>()
        while (current != rootId && seen.add(current)) {
            current = parentOf(groupRef(current)) ?: return Int.MAX_VALUE
            depth += 1
        }
        return if (current == rootId) depth else Int.MAX_VALUE
    }

    /** The height of [groupId]'s own group subtree; one when it has no child groups. */
    fun groupSubtreeHeight(groupId: String): Int {
        val childrenInTree = childrenOf(groupId)
        var tallest = 0
        for (ref in childrenInTree) {
            if (!isGroupRef(ref)) continue
            tallest = maxOf(tallest, groupSubtreeHeight(refId(ref)))
        }
        return 1 + tallest
    }

    /** Moves [ref] under [newParent] at [index], or returns `null` when that would break the tree. */
    fun withMoved(ref: String, newParent: String, index: Int): GroupLayout? {
        if (!children.containsKey(newParent)) return null
        val oldParent = parentOf(ref) ?: return null
        if (ref == groupRef(rootId)) return null

        if (isGroupRef(ref)) {
            val moved = refId(ref)
            if (moved == newParent || isUnder(newParent, moved)) return null
            val parentDepth = depthOfGroup(newParent)
            if (parentDepth == Int.MAX_VALUE) return null
            val newDepth = parentDepth + 1
            if (newDepth + groupSubtreeHeight(moved) - 1 > MAX_GROUP_DEPTH) return null
        }

        val updated = children.mapValues { it.value.toMutableList() }.toMutableMap()
        val source = updated[oldParent] ?: return null
        val removed = source.remove(ref)
        if (!removed) return null

        val target = updated.getOrPut(newParent) { mutableListOf() }
        // [index] is the position in the list after the source has been removed, which makes
        // "move down one" and "move up one" both simple shifts.
        val at = index.coerceIn(0, target.size)
        target.add(at, ref)
        return GroupLayout(rootId, updated.mapValues { it.value.toList() })
    }

    /** Adds a newly created group under [parentId], or returns `null` when the depth is full. */
    fun withNewGroup(groupId: String, parentId: String, index: Int): GroupLayout? {
        if (!children.containsKey(parentId)) return null
        if (children.values.any { groupRef(groupId) in it }) return null
        if (depthOfGroup(parentId) + 1 > MAX_GROUP_DEPTH) return null
        if (parentId == groupId) return null

        val updated = children.mapValues { it.value.toMutableList() }.toMutableMap()
        val target = updated.getOrPut(parentId) { mutableListOf() }
        target.add(index.coerceIn(0, target.size), groupRef(groupId))
        updated.getOrPut(groupId) { mutableListOf() }
        return GroupLayout(rootId, updated.mapValues { it.value.toList() })
    }

    /** Every group in pre-order, the root first. */
    fun groupsPreOrder(): List<String> {
        val order = mutableListOf<String>()
        fun visit(groupId: String) {
            order += groupId
            childrenOf(groupId).forEach { ref ->
                if (isGroupRef(ref)) visit(refId(ref))
            }
        }
        visit(rootId)
        return order
    }

    /** Writes the ordering of each group's direct entries back to the vault. */
    fun applyEntryOrder(vault: Vault) {
        for (group in vault.groups()) {
            val direct = childrenOf(group.id).filter { isEntryRef(it) }.map { refId(it) }
            if (direct != group.entryIds) vault.setOrdering(group.id, direct)
        }
    }

    /** The flat serialization stored in the preferences. */
    fun serialize(): String =
        buildString {
            append(HEADER).append('\n')
            // Every group gets a line, including an empty one: a group with no children still
            // has a place in the tree.
            children.forEach { (parent, refs) ->
                append(parent).append('|').append(refs.joinToString(",")).append('\n')
            }
        }

    private fun isUnder(candidate: String, ancestor: String): Boolean {
        var current = candidate
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            if (current == ancestor) return true
            current = parentOf(groupRef(current)) ?: return false
        }
        return false
    }

    companion object {
        /** The named group's maximum depth; the top group is depth zero. */
        const val MAX_GROUP_DEPTH: Int = 3

        private const val HEADER = "Q2FAGL1"

        /** The tree a vault has before the user has arranged anything. */
        fun fromVault(vault: Vault): GroupLayout {
            val groups = vault.groups()
            val root = groups.firstOrNull { it.top } ?: return empty(vault)
            val named = groups.filterNot { it.top }
            val children = mutableMapOf<String, List<String>>()
            children[root.id] =
                root.entryIds.map(::entryRef) + named.map { groupRef(it.id) }
            named.forEach { group ->
                children[group.id] = group.entryIds.map(::entryRef)
            }
            return GroupLayout(root.id, children)
        }

        /** Parses [stored] and makes it agree with what [vault] currently holds. */
        fun parse(stored: String?, vault: Vault): GroupLayout {
            if (stored.isNullOrBlank()) return fromVault(vault)
            val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.firstOrNull() != HEADER) return fromVault(vault)

            val parsed = mutableMapOf<String, List<String>>()
            for (line in lines.drop(1)) {
                val separator = line.indexOf('|')
                if (separator <= 0) continue
                val parent = line.substring(0, separator)
                val refs =
                    line.substring(separator + 1)
                        .split(',')
                        .filter { it.length > 2 && (it.startsWith("g:") || it.startsWith("e:")) }
                parsed[parent] = refs
            }
            return GroupLayout(vault.groups().firstOrNull { it.top }?.id ?: return fromVault(vault), parsed)
                .reconciled(vault)
        }

        private fun empty(vault: Vault): GroupLayout {
            val root = vault.groups().firstOrNull { it.top }?.id.orEmpty()
            return GroupLayout(root, mapOf(root to emptyList()))
        }
    }

    /** Reconciles this tree with the identifiers that still exist in [vault]. */
    fun reconciled(vault: Vault): GroupLayout {
        val groups = vault.groups()
        val root = groups.firstOrNull { it.top } ?: return this
        val validGroups = groups.associateBy { it.id }
        val entries = vault.entries()
        val entryIds = entries.map { it.id }.toSet()
        val membership = entries.associate { it.id to (vault.groupOf(it.id) ?: root.id) }
        val saved = children
        val out = mutableMapOf<String, MutableList<String>>()
        val placedGroups = mutableSetOf(root.id)
        val placedEntries = mutableSetOf<String>()

        fun ensure(groupId: String) {
            out.getOrPut(groupId) { mutableListOf() }
        }

        fun place(parentId: String, parentDepth: Int) {
            ensure(parentId)
            val destination = out.getValue(parentId)
            for (ref in saved[parentId].orEmpty()) {
                if (isGroupRef(ref)) {
                    val id = refId(ref)
                    if (id == root.id || id !in validGroups || id in placedGroups) continue
                    if (parentDepth + 1 > MAX_GROUP_DEPTH) continue
                    placedGroups += id
                    destination += groupRef(id)
                    place(id, parentDepth + 1)
                } else {
                    val id = refId(ref)
                    if (id in entryIds && id !in placedEntries && membership[id] == parentId) {
                        placedEntries += id
                        destination += entryRef(id)
                    }
                }
            }
        }

        place(root.id, 0)

        // Groups the saved tree did not reach (new, or detached by a deletion) become children
        // of the root, in the vault's own group order.
        for (group in groups) {
            if (group.id == root.id || group.id in placedGroups) continue
            ensure(group.id)
            out.getValue(root.id) += groupRef(group.id)
            placedGroups += group.id
            place(group.id, 1)
        }

        // Every group still needs a line, even when it has no children.
        for (group in groups) ensure(group.id)

        // Entries the saved tree did not place go to the group the vault says they belong to,
        // in that group's own order. This is what makes a sync, an import or a deletion heal
        // the view instead of dropping entries out of it.
        for (group in groups) {
            for (entryId in group.entryIds) {
                if (entryId in placedEntries || entryId !in entryIds) continue
                out.getOrPut(group.id) { mutableListOf() } += entryRef(entryId)
                placedEntries += entryId
            }
        }
        for (entry in entries) {
            if (entry.id in placedEntries) continue
            out.getOrPut(root.id) { mutableListOf() } += entryRef(entry.id)
            placedEntries += entry.id
        }

        return GroupLayout(root.id, out.mapValues { it.value.toList() })
    }
}

/** The vault's groups in the tree's pre-order, with any unknown group appended. */
internal fun orderedGroups(vault: Vault, layout: GroupLayout): List<Group> {
    val byId = vault.groups().associateBy { it.id }
    val ordered = layout.groupsPreOrder().mapNotNull { byId[it] }.toMutableList()
    val known = ordered.map { it.id }.toSet()
    ordered += vault.groups().filter { it.id !in known }
    return ordered.sortedBy { if (it.top) 0 else 1 }
}

/** A tree reference to a group. */
internal fun groupRef(id: String): String = "g:$id"

/** A tree reference to an entry. */
internal fun entryRef(id: String): String = "e:$id"

/** Whether [ref] names a group. */
internal fun isGroupRef(ref: String): Boolean = ref.startsWith("g:")

/** Whether [ref] names an entry. */
internal fun isEntryRef(ref: String): Boolean = ref.startsWith("e:")

/** The identifier inside [ref]. */
internal fun refId(ref: String): String = ref.substring(2)
