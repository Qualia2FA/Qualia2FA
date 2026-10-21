package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which groups get a header on the 2FA screen.
 *
 * The rule that matters is the one this suite was written for: a group that holds **only child
 * groups** has no cards of its own, and an earlier version dropped it — the child's header then
 * appeared indented under nothing, and the tree could not be read. The counts and the visibility
 * are pure functions so that rule is tested without a window.
 */
class TreeVisibilityTest {
    private fun group(
        id: String,
        name: String,
        top: Boolean = false,
        entries: List<String> = emptyList(),
        children: List<GroupChild> = emptyList(),
    ) = Group(id = id, name = name, top = top, children = children, entryIds = entries)

    private val tree =
        listOf(
            group(
                "top",
                "",
                top = true,
                entries = listOf("mail"),
                children = listOf(GroupChild.Group("work"), GroupChild.Group("personal")),
            ),
            group("work", "Work", children = listOf(GroupChild.Group("github"))),
            group("github", "GitHub", entries = listOf("github-entry")),
            group("personal", "Personal", entries = listOf("steam")),
            group("empty", "Empty"),
        )

    @Test
    fun aGroupThatHoldsOnlyChildGroupsIsStillCounted() {
        val counts = subtreeEntryCounts(tree)
        assertEquals(1, counts["work"], "Work holds no card of its own but one in its child")
        assertEquals(1, counts["github"])
        assertEquals(3, counts["top"], "the top group's entry plus the two entries under Work and Personal")
        assertEquals(0, counts["empty"], "a group with nothing under it keeps nothing alive")
    }

    @Test
    fun aGroupWithNoEntriesAnywhereIsNotShown() {
        val counts = subtreeEntryCounts(tree)
        val visible = visibleGroupIds(tree, counts, hasOrphans = false)
        assertTrue("work" in visible, "the parent of a non-empty child must be shown")
        assertFalse("empty" in visible)
    }

    @Test
    fun theTopGroupIsKeptForEntriesThatBelongToNoGroup() {
        val counts = subtreeEntryCounts(tree)
        assertTrue("top" in visibleGroupIds(tree, counts, hasOrphans = true))
        // With no own entries and no orphans the top group has nothing to draw, so a header for
        // it would be an empty section.
        val emptyTop = listOf(group("top", "", top = true), group("work", "Work", entries = listOf("e")))
        assertFalse("top" in visibleGroupIds(emptyTop, subtreeEntryCounts(emptyTop), hasOrphans = false))
    }
}
