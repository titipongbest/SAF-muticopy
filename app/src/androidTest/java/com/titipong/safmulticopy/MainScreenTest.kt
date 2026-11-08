package com.titipong.safmulticopy

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.titipong.safmulticopy.data.FolderPair
import com.titipong.safmulticopy.data.PairStore
import com.titipong.safmulticopy.engine.CopyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The real screen, driven like a user would; screenshots are saved for review. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun clean() {
        CopyManager.reset()
        val store = PairStore.get(T.ctx)
        store.pairs.value.forEach { store.delete(it.id) }
        T.ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    @After fun close() {
        scenario?.close()
    }

    private fun settings(block: android.content.SharedPreferences.Editor.() -> Unit) =
        T.ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true, action = block)

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 60_000) {
        try {
            waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            T.screenshot("FAILED-waiting-for-${text.replace(Regex("[^A-Za-z0-9]"), "_")}")
            throw e
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400) // let animations settle
        T.screenshot(name)
    }

    private fun pair(id: String, name: String, src: File, dest: File, mirror: Boolean = false) = FolderPair(
        id = id,
        name = name,
        source = T.tree(src).toString(),
        sourceName = src.name,
        sourceLocation = "Test storage",
        destination = T.tree(dest).toString(),
        destName = dest.name,
        destLocation = "Test storage",
        mirror = mirror,
    )

    @Test fun emptyState_tabs_andCollapsedExcludes() {
        launch()
        compose.onNodeWithText("No folder pairs yet").assertExists()
        shot("01-pairs-empty")

        compose.onNodeWithText("Quick copy").performClick()
        compose.onNodeWithText("Copy from").assertExists()
        compose.onNodeWithText("Choose destination").assertExists()
        compose.onAllNodesWithText("wildcards", substring = true).fetchSemanticsNodes().let {
            assertEquals("excludes are hidden at first", 0, it.size)
        }
        shot("02-quick-copy-empty")

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Exclude files or folders"))
        compose.onNodeWithText("Exclude files or folders").performClick()
        compose.waitForText("Separate with ;", 5_000)
        shot("03-excludes-open")
    }

    @Test fun folderPairs_syncAll_runsEachPair_thenShowsItsOwnFiles() {
        val base = T.dir("ui-pairs")
        val s1 = File(base, "Camera").apply { mkdirs() }
        val s2 = File(base, "Documents").apply { mkdirs() }
        repeat(25) { T.write(s1, "IMG_$it.jpg", "photo $it") }
        repeat(10) { T.write(s2, "doc_$it.txt", "doc $it") }
        val d1 = File(base, "Backup-Camera").apply { mkdirs() }
        val d2 = File(base, "Backup-Docs").apply { mkdirs() }
        PairStore.get(T.ctx).upsert(pair("A", "Pair A", s1, d1))
        PairStore.get(T.ctx).upsert(pair("B", "Pair B", s2, d2))
        settings { putBoolean("keepDates", false) }

        launch()
        compose.onNodeWithText("Folder pairs (2)").assertExists()
        shot("04-pairs-list")

        compose.onNodeWithText("Sync all (2)").performClick()
        compose.waitForText("All pairs synced")
        shot("05-pairs-synced")

        assertEquals(T.contents(s1), T.contents(d1))
        assertEquals(T.contents(s2), T.contents(d2))

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("New folder pair"))
        compose.onAllNodesWithText("Pair A").onLast().performClick()
        compose.waitForText("From this run", 10_000)
        compose.onAllNodesWithText("IMG_", substring = true).fetchSemanticsNodes().let {
            assertFalse("pair A shows its own files", it.isEmpty())
        }
        assertEquals(
            "pair A's details don't show pair B's files",
            0,
            compose.onAllNodesWithText("doc_", substring = true).fetchSemanticsNodes().size,
        )
        shot("06-pair-details")
    }

    @Test fun quickCopy_asksAboutDates_confirmsMirror_andCopies() {
        val base = T.dir("ui-quick")
        val src = File(base, "Music").apply { mkdirs() }
        repeat(12) { T.write(src, "track_$it.mp3", "song $it") }
        val dest = File(base, "USB").apply { mkdirs() }
        T.write(dest, "Music/old_track.mp3", "should be removed by mirror")
        settings {
            putInt("tab", 1)
            putString("sources", T.tree(src).toString())
            putString("destination", T.tree(dest).toString())
            putBoolean("mirror", true)
            putBoolean("keepDates", true)
        }

        assertFalse("run without All files access", T.hasAllFilesAccess())
        launch()
        compose.onNodeWithText("Music").assertExists()
        shot("07-quick-ready")

        compose.onNodeWithText("Start copy").performClick()
        compose.waitForText("Keep the original dates?", 5_000)
        shot("08-dates-dialog")
        compose.onNodeWithText("Copy with today's date").performClick()

        compose.waitForText("Mirror will delete files", 5_000)
        shot("09-mirror-dialog")
        compose.onNodeWithText("Delete & copy").performClick()

        compose.waitForText("All done")
        shot("10-quick-done")
        assertEquals(T.contents(src), T.contents(File(dest, "Music")))
        assertFalse(File(dest, "Music/old_track.mp3").exists())

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Recent files"))
        shot("11-quick-recent-files")
    }
}
