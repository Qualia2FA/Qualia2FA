package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group tree, which lives in the vault since format version 4.
 *
 * These tests cover the two things that can go wrong when the tree moved out of the preferences:
 * a layout that does not describe the vault must write nothing, and the old saved layout has to
 * arrive in the vault exactly once.
 */
class GroupLayoutTest {
    private fun vault(): Vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))

    private fun add(vault: Vault, name: String, secret: String): String =
        assertNotNull(
            vault.addEntry(name, "Example", "alice", secret, OtpAlgorithm.Sha1, 6, EntryKind.Totp(30), "", 1_700_000_000L),
        )

    @Test
    fun aTreeIsWrittenIntoTheVaultAndReadBackFromIt() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val first = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val second = add(vault, "Second", "GEZDGNBVGY3TQOJQ")
        val work = assertNotNull(vault.createGroup("Work"))
        val child = assertNotNull(vault.createGroupIn(work, "Child"))
        assertTrue(vault.assignToGroup(second, child))

        var layout = GroupLayout.fromVault(vault)
        layout = assertNotNull(layout.withMoved(entryRef(first), work, 0))
        assertTrue(layout.applyTo(vault), "the tree must be stored in the vault")

        val stored = assertNotNull(vault.groups().firstOrNull { it.id == work })
        assertEquals(
            listOf(GroupChild.Entry(first), GroupChild.Group(child)),
            stored.children,
            "the mixed order of entries and child groups is what the vault stores",
        )
        assertEquals(work, vault.parentOf(child))
        assertEquals(2, vault.depthOf(child) ?: -1, "the entry's group and the child group nest")
        assertEquals(
            work,
            vault.groupOf(first),
            "moving an entry in the tree moves its vault membership",
        )

        // The file carries the tree; opening it gives the same arrangement back.
        val data = vault.serialize()
        assertNotNull(data)
        val reopened = assertNotNull(Qualia.openVault(data, store), "the file opens with the key the store holds")
        assertEquals(
            reopened.groups().first { it.top }.id,
            reopened.parentOf(work),
            "Work hangs from the top group",
        )
        assertEquals(
            listOf(GroupChild.Entry(first), GroupChild.Group(child)),
            reopened.groups().first { it.id == work }.children,
        )
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
    fun aTreeThatNamesSomethingMissingWritesNothing() {
        val vault = vault()
        val entry = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val work = assertNotNull(vault.createGroup("Work"))
        val before = vault.groups().map { it.id to it.children }

        // A reference to an entry that is not in the vault, as a screen left open while another
        // device deleted it would hold.
        val root = vault.groups().first { it.top }.id
        val stale =
            GroupLayout.forTest(
                root,
                mapOf(
                    root to listOf(entryRef(entry), entryRef("00".repeat(16))),
                    work to emptyList(),
                ),
            )
        assertFalse(stale.applyTo(vault), "a tree the vault cannot hold must be refused")
        assertEquals(before, vault.groups().map { it.id to it.children }, "and nothing may have been written")
    }

    @Test
    fun aGroupMovesWithItsSubtree() {
        val vault = vault()
        val entry = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val source = assertNotNull(vault.createGroup("Source"))
        val nested = assertNotNull(vault.createGroupIn(source, "Nested"))
        val target = assertNotNull(vault.createGroup("Target"))
        assertTrue(vault.assignToGroup(entry, nested))

        var layout = GroupLayout.fromVault(vault)
        layout = assertNotNull(
            layout.withMoved(groupRef(source), target, layout.childrenOf(target).size),
            "the source group should be allowed under the target",
        )
        assertTrue(layout.applyTo(vault))

        assertEquals(target, vault.parentOf(source))
        assertEquals(source, vault.parentOf(nested))
        assertEquals(3, vault.depthOf(nested) ?: -1)
        assertEquals(nested, vault.groupOf(entry), "the subtree keeps its entry")
    }

    @Test
    fun theOldLayoutIsMigratedOnceAndThenDeleted() {
        val vault = vault()
        val first = add(vault, "First", "JBSWY3DPEHPK3PXP")
        val second = add(vault, "Second", "GEZDGNBVGY3TQOJQ")
        val work = assertNotNull(vault.createGroup("Work"))
        assertTrue(vault.assignToGroup(first, work))

        val settings = RecordingSettings()
        settings.stored =
            "Q2FAGL1\n" +
                "${vault.groups().first { it.top }.id}|e:$first,e:$second\n" +
                "$work|e:$first\n"
        assertTrue(migrateLegacyGroupLayout(settings, vault), "the migration must be reported")
        assertNull(settings.stored, "the old key is deleted only after the write succeeded")
        assertEquals(
            listOf(GroupChild.Entry(second), GroupChild.Group(work)),
            vault.groups().first { it.top }.children,
            "the saved order is what the top group now holds; the group itself is attached after its entries",
        )
        assertEquals(work, vault.groupOf(first))
    }

    @Test
    fun aMissingOrDamagedOldLayoutIsNotAnError() {
        val vault = vault()
        add(vault, "First", "JBSWY3DPEHPK3PXP")
        val settings = RecordingSettings()
        assertFalse(migrateLegacyGroupLayout(settings, vault), "nothing stored, nothing to migrate")

        settings.stored = "not the old header"
        assertTrue(migrateLegacyGroupLayout(settings, vault), "an unreadable layout is dropped")
        assertNull(settings.stored)
    }

    /** A settings store that keeps the legacy layout in memory. */
    private class RecordingSettings : SettingsStore {
        var stored: String? = null

        override fun language(): AppLanguage = AppLanguage.System

        override fun setLanguage(language: AppLanguage) = Unit

        override fun theme(): AppTheme = AppTheme.System

        override fun setTheme(theme: AppTheme) = Unit

        override fun legacyGroupLayout(): String? = stored

        override fun clearLegacyGroupLayout() {
            stored = null
        }
    }
}
