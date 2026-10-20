package dev.qualia.qualia2fa

/**
 * A view of the vault's group tree, with each parent's children in display order.
 *
 * # It is a view, not the truth
 *
 * The tree used to live beside the preferences; since format version 4 it is part of the vault,
 * because the nesting and the mixed order of entries and child groups are what the user arranged
 * and what has to sync. [fromVault] builds this view from the vault and [applyTo] writes it back;
 * nothing is stored anywhere else.
 *
 * A reference is `g:<group id>` or `e:<entry id>`. Both identifiers are hexadecimal, so the
 * serialized form needs no escaping beyond the `|` and `,` separators.
 *
 * # The depth limit
 *
 * The top group is depth zero. Named groups may be nested up to [MAX_GROUP_DEPTH]; entries may
 * sit at any depth, including inside the deepest group. A group placed deeper is rejected by
 * [withMoved], and the vault refuses the list as well - the two must agree, or the interface
 * would draw a tree the vault cannot store.
 *
 * # Writing is all-or-nothing
 *
 * [applyTo] validates the whole tree first, then writes it: the vault is flattened and rebuilt
 * parent-first, so a group can be moved from inside one subtree to another without the
 * intermediate states being rejected as cycles. A tree that fails validation writes nothing.
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

    /** Whether this tree has exactly the same children lists as [other]. */
    fun sameAs(other: GroupLayout): Boolean = rootId == other.rootId && children == other.children

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
        val seen = mutableSetOf<String>()
        fun visit(groupId: String) {
            if (!seen.add(groupId)) return
            order += groupId
            childrenOf(groupId).forEach { ref ->
                if (isGroupRef(ref)) visit(refId(ref))
            }
        }
        visit(rootId)
        return order
    }

    /**
     * Whether this tree can be written into [vault].
     *
     * Checked before anything is written: every group must appear exactly once, every reference
     * must name something the vault holds, the nesting must be within [MAX_GROUP_DEPTH], and no
     * parent chain may loop. A stale screen is the normal reason a check fails - the user deleted
     * an entry on another device, or a sync changed the tree - and the answer is to reload rather
     * than to write a tree that no longer describes the vault.
     */
    fun validate(vault: Vault): Boolean {
        val groups = vault.groups()
        val groupIds = groups.map { it.id }.toSet()
        rule("the root must be the top group") { rootId in groupIds && groups.firstOrNull { it.top }?.id == rootId }
        rule("every group must have a line") { groupIds.all { it in children } }
        rule("a line must not name a group the vault does not hold") {
            children.values.flatten().none { isGroupRef(it) && refId(it) !in groupIds }
        }

        val entryIds = vault.entries().map { it.id }.toSet()
        val seenRefs = mutableSetOf<String>()
        for (refs in children.values) {
            for (ref in refs) {
                rule("a reference must be well formed") { isEntryRef(ref) || isGroupRef(ref) }
                rule("a reference must appear exactly once") { seenRefs.add(ref) }
                if (isEntryRef(ref)) {
                    rule("an entry reference must name an entry the vault holds") { refId(ref) in entryIds }
                }
            }
        }
        for (groupId in groupIds) {
            if (groupId == rootId) continue
            rule("every named group must be listed") { groupRef(groupId) in seenRefs }
        }

        val preOrder = groupsPreOrder()
        rule("the walk must reach every group exactly once") {
            preOrder.size == groupIds.size && preOrder.toSet() == groupIds
        }
        rule("no group may be nested deeper than the limit") {
            groupIds.all { depthOfGroup(it) in 0..MAX_GROUP_DEPTH }
        }
        return true
    }

    /** Runs [check], remembering a failure so that [validate] can report one verdict. */
    private inline fun rule(what: String, check: () -> Boolean): Boolean {
        val passed = check()
        if (!passed) lastViolation = what
        return passed
    }

    /** The last rule [validate] saw fail, for a message or a test. */
    var lastViolation: String? = null
        private set

    /**
     * Writes this tree into [vault], or returns false and leaves it untouched.
     *
     * # Why the vault is flattened first
     *
     * Writing one group at a time can fail on a legitimate rearrangement: moving A under B while
     * B is still under A is a cycle step by step, even though the result is a tree. Flattening
     * first removes every group-to-group reference, so the rebuild parent-first can put any tree
     * in place without a transient cycle.
     */
    fun applyTo(vault: Vault): Boolean {
        lastViolation = null
        if (!validate(vault)) return false
        val current = fromVault(vault)
        if (current.sameAs(this)) return true

        val snapshot = current
        val groups = vault.groups()
        for (group in groups) {
            if (!vault.setChildren(group.id, emptyList())) {
                snapshot.applyUnchecked(vault)
                return false
            }
        }
        if (applyUnchecked(vault)) return true
        snapshot.applyUnchecked(vault)
        return false
    }

    /** The build half of [applyTo]; assumes the vault has already been flattened. */
    private fun applyUnchecked(vault: Vault): Boolean {
        for (groupId in groupsPreOrder()) {
            val refs = childrenOf(groupId)
            if (refs.isEmpty()) continue
            val children =
                refs.mapNotNull { ref ->
                    when {
                        isEntryRef(ref) -> GroupChild.Entry(refId(ref))
                        isGroupRef(ref) -> GroupChild.Group(refId(ref))
                        else -> null
                    }
                }
            if (children.size != refs.size) return false
            if (!vault.setChildren(groupId, children)) return false
        }
        return true
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
        val MAX_GROUP_DEPTH: Int
            get() = Qualia.maxGroupDepth

        /** The header of the tree as version 0.2.x stored it beside the preferences. */
        private const val LEGACY_HEADER = "Q2FAGL1"

        /** The tree [vault] currently holds. */
        fun fromVault(vault: Vault): GroupLayout {
            val groups = vault.groups()
            val root = groups.firstOrNull { it.top } ?: return empty()
            val children =
                groups.associate { group ->
                    group.id to
                        group.children.map { child ->
                            when (child) {
                                is GroupChild.Entry -> entryRef(child.id)
                                is GroupChild.Group -> groupRef(child.id)
                            }
                        }
                }
            return GroupLayout(root.id, children)
        }

        /**
         * Reads the tree a 0.2.x installation kept in `settings.properties`.
         *
         * Used **once**, to migrate: the result is written into the vault and the setting is then
         * deleted. When there is nothing stored, or the stored text is not the old header, the
         * vault's own tree is returned so that a missing or damaged layout does not stop the
         * application from opening.
         */
        fun parse(stored: String?, vault: Vault): GroupLayout {
            if (stored.isNullOrBlank()) return fromVault(vault)
            val lines = stored.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.firstOrNull() != LEGACY_HEADER) return fromVault(vault)

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
            val root = vault.groups().firstOrNull { it.top }?.id ?: return fromVault(vault)
            return GroupLayout(root, parsed).reconciled(vault)
        }

        private fun empty(): GroupLayout = GroupLayout("", emptyMap())

        /**
         * Builds a view directly from its lists.
         *
         * Test-only: production code gets a view from [fromVault] and writes it with [applyTo].
         * It exists so that a test can describe a tree the vault would not produce - a stale
         * screen, for instance - without going through the vault first.
         */
        internal fun forTest(rootId: String, children: Map<String, List<String>>): GroupLayout =
            GroupLayout(rootId, children)
    }

    /**
     * Repairs a tree against the identifiers [vault] still holds.
     *
     * This is the migration path, where the saved tree can name entries and groups that were
     * deleted since it was written, and the one place an entry's vault membership has to agree
     * with where the saved tree draws it. Once the tree lives in the vault the vault itself is
     * the reconciliation, so this is not needed on the normal path.
     */
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

/** A tree reference to a group. */
internal fun groupRef(id: String): String = "g:$id"

/**
 * Writes the tree a 0.2.x installation kept in the preferences into [vault], once.
 *
 * The setting is deleted only after the write succeeded: a failed migration keeps the old text
 * so that the next start can try again, whereas deleting it first would lose the arrangement.
 * A missing or unreadable layout is not an error - the vault's own tree, which is flat for a
 * version 3 file, is what the user gets.
 *
 * Returns whether the preferences were changed.
 */
internal fun migrateLegacyGroupLayout(settings: SettingsStore, vault: Vault): Boolean {
    val stored = settings.legacyGroupLayout() ?: return false
    val target = GroupLayout.parse(stored, vault)
    if (!target.applyTo(vault)) return false
    settings.clearLegacyGroupLayout()
    return true
}
/** A tree reference to an entry. */
internal fun entryRef(id: String): String = "e:$id"

/** Whether [ref] names a group. */
internal fun isGroupRef(ref: String): Boolean = ref.startsWith("g:")

/** Whether [ref] names an entry. */
internal fun isEntryRef(ref: String): Boolean = ref.startsWith("e:")

/** The identifier inside [ref]. */
internal fun refId(ref: String): String = ref.substring(2)
