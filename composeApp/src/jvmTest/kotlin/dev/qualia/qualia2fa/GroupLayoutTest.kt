package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tree stored beside the preferences.
 *
 * These tests keep the shape that the vault cannot express: parent groups, a mixed order of
 * entries and subgroups, and the depth limit. They also pin that a tree read back always
 * agrees with the identifiers the vault still holds.
 */
class GroupLayoutTest {
    private fun vault(): Vault {
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))
        return vault
    }

    private fun add(vault: Vault, name: String, secret: String): String =
        assertNotNull(
            vault.addEntry(name, "Example", "alice", secret, OtpAlgorithm.Sha1, 6, EntryKind.Totp(30), "", 1_700_000_000L),
        )

    @Test
    fun aTreeSurvivesSerializationWithinTheVault() {
        val vault = vault()
        val first = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val second = add(vault, "Second", "GEZDGNBVGY3TQOJQ")
        val work = assertNotNull(vault.createGroup("Work"))
        val child = assertNotNull(vault.createGroup("Child"))
        vault.assignToGroup(second, child)

        var layout = GroupLayout.fromVault(vault)
        layout = assertNotNull(layout.withMoved(groupRef(child), work, layout.childrenOf(work).size))
        layout = assertNotNull(layout.withMoved(entryRef(first), work, 0))
        // The tree and the vault must agree about which group the entry belongs to; the tree
        // only decides its position inside that group.
        assertTrue(vault.assignToGroup(first, work))

        val stored = layout.serialize()
        val parsed = GroupLayout.parse(stored, vault)

        assertEquals(listOf(entryRef(first), groupRef(child)), parsed.childrenOf(work))
        assertEquals(listOf(entryRef(second)), parsed.childrenOf(child))
        assertEquals(1, parsed.depthOfGroup(work))
        assertEquals(2, parsed.depthOfGroup(child))
    }

    @Test
    fun theFourthGroupLevelIsRefusedButAnEntryAtItIsNot() {
        val vault = vault()
        val entry = add(vault, "Deep entry", "MZXW6YTBOI======")
        val one = assertNotNull(vault.createGroup("One"))
        val two = assertNotNull(vault.createGroup("Two"))
        val three = assertNotNull(vault.createGroup("Three"))
        val four = assertNotNull(vault.createGroup("Four"))

        var layout = GroupLayout.fromVault(vault)
        layout = assertNotNull(layout.withMoved(groupRef(two), one, layout.childrenOf(one).size))
        layout = assertNotNull(layout.withMoved(groupRef(three), two, layout.childrenOf(two).size))

        assertNull(
            layout.withMoved(groupRef(four), three, layout.childrenOf(three).size),
            "a group below a depth-three group would be a fourth level",
        )
        assertNotNull(
            layout.withMoved(entryRef(entry), three, layout.childrenOf(three).size),
            "an entry may sit in the deepest group",
        )
    }

    @Test
    fun reconcilingAddsNewIdentifiersAndDropsMissingOnes() {
        val vault = vault()
        val first = add(vault, "First", "JBSWY3DPEHPK3PXP")
        var layout = GroupLayout.fromVault(vault)

        val later = assertNotNull(vault.createGroup("Later"))
        val second = add(vault, "Second", "GEZDGNBVGY3TQOJQ")
        vault.assignToGroup(second, later)

        val reconciled = layout.reconciled(vault)
        assertTrue(groupRef(later) in reconciled.childrenOf(reconciled.rootId))
        assertTrue(entryRef(second) in reconciled.childrenOf(later))
        assertTrue(entryRef(first) in reconciled.childrenOf(reconciled.rootId))

        vault.removeEntry(first, 1_700_000_001L)
        layout = reconciled.reconciled(vault)
        assertTrue(entryRef(first) !in layout.childrenOf(layout.rootId))
    }

    @Test
    fun applyingTheTreeWritesTheGroupsEntryOrder() {
        val vault = vault()
        val first = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val second = add(vault, "Second", "GEZDGNBVGY3TQOJQ")
        val group = assertNotNull(vault.createGroup("Work"))
        vault.assignToGroup(first, group)
        vault.assignToGroup(second, group)

        var layout = GroupLayout.fromVault(vault)
        layout = assertNotNull(layout.withMoved(entryRef(second), group, 0))
        layout.applyEntryOrder(vault)

        assertEquals(listOf(second, first), vault.groups().single { it.id == group }.entryIds)
    }
}
