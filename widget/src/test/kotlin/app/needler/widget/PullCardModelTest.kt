package app.needler.widget

import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullState
import app.needler.widget.pulls.PullCardModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing one pull out of the queue, and folding it onto the card.
 *
 * This is the part of the pull widget that can be wrong without anything crashing, and there are five
 * ways for it to be: showing the wrong pull out of several, drawing a percentage for a pull that has
 * not started, drawing a finished pull as though it were still going, losing the count of what else is
 * in the queue, and putting a blank line where the album title should be. One test each, and two for
 * the last, because that one has already cost a release.
 */
class PullCardModelTest {

    @Test
    fun `an empty queue is the resting card, not an error`() {
        val model = PullCardModel.of(emptyList())

        assertEquals(PullCardModel.Idle, model)
        assertFalse(model.hasActivePull)
        assertNull(model.percent)
        assertNull(model.stage)
        // Null fraction, drawn as an empty track rather than as a bar at zero.
        assertNull(model.fraction)
        assertEquals(0f, model.barFraction, 0f)
        assertFalse(model.showsCount)
        assertFalse(model.needsStageLabel)
        assertFalse(model.hasAlbumTitle)
    }

    @Test
    fun `one downloading pull is the card the pack draws`() {
        // design/html/15-Widget.html: 62%, Black Classical Music, the bar at 62%.
        val model = PullCardModel.of(listOf(WidgetFixtures.downloading()))

        assertTrue(model.hasActivePull)
        assertEquals("Black Classical Music", model.albumTitle)
        assertTrue(model.hasAlbumTitle)
        assertEquals("62%", model.percent)
        assertEquals(0.62f, model.barFraction, 0.005f)
        assertEquals(PullState.DOWNLOADING, model.stage)
        // A number already says this pull is downloading, so the stage word is left off.
        assertFalse(model.needsStageLabel)
        // One pull, so the eyebrow stays the pack's plain "Pulls".
        assertFalse(model.showsCount)
        assertEquals(1, model.activeCount)
    }

    @Test
    fun `the pull nearest the end is the one drawn, not the newest`() {
        // The repository hands these over newest first, so taking the head would have shown a pull
        // placed a minute ago and still searching over one that is about to land.
        val pulls = listOf(
            WidgetFixtures.searching("Bruise"),
            WidgetFixtures.downloading("Black Classical Music", percent = 12),
            WidgetFixtures.downloading("Promises", percent = 94),
        )

        val model = PullCardModel.of(pulls)

        assertEquals("Promises", model.albumTitle)
        assertEquals("94%", model.percent)
        assertEquals(3, model.activeCount)
        assertTrue(model.showsCount)
    }

    @Test
    fun `two pulls at the same percentage do not flicker between redraws`() {
        // Ties fall back to the repository's own order, which is newest first, so the same pull is
        // chosen every time the card is composed.
        val pulls = listOf(
            WidgetFixtures.downloading("Promises", percent = 40),
            WidgetFixtures.downloading("Bruise", percent = 40),
        )

        assertEquals("Promises", PullCardModel.of(pulls).albumTitle)
        assertEquals("Promises", PullCardModel.of(pulls).albumTitle)
    }

    @Test
    fun `a pull with no reported progress draws its stage instead of a number`() {
        val model = PullCardModel.of(listOf(WidgetFixtures.searching("Bruise")))

        assertTrue(model.hasActivePull)
        assertEquals("Bruise", model.albumTitle)
        // Not "0%": a pull still looking for sources has not started.
        assertNull(model.percent)
        assertNull(model.fraction)
        assertEquals(PullState.SEARCHING, model.stage)
        assertTrue(model.needsStageLabel)
    }

    @Test
    fun `a searching pull is still drawn when a finished one is newer`() {
        // maxByOrNull over a null fraction must not pick the completed pull just because it reports
        // a hundred percent: it is filtered out before the choice is made.
        val pulls = listOf(
            WidgetFixtures.completed("Blue Rev"),
            WidgetFixtures.searching("Bruise"),
        )

        val model = PullCardModel.of(pulls)

        assertEquals("Bruise", model.albumTitle)
        assertEquals(1, model.activeCount)
    }

    @Test
    fun `a queue of nothing but finished pulls is the resting card`() {
        // A bucket is a derived property of a state, so a caller passing the wrong list must not
        // leave the card claiming a hundred percent over an album that already landed.
        val model = PullCardModel.of(listOf(WidgetFixtures.completed("Blue Rev")))

        assertEquals(PullCardModel.Idle, model)
    }

    @Test
    fun `the count is the whole active queue, not the pulls with numbers`() {
        val pulls = listOf(
            WidgetFixtures.searching("Bruise"),
            WidgetFixtures.searching("Promises"),
            WidgetFixtures.downloading("Black Classical Music", percent = 5),
            WidgetFixtures.completed("Blue Rev"),
        )

        val model = PullCardModel.of(pulls)

        assertEquals("Black Classical Music", model.albumTitle)
        // Three active; the completed one is not part of what the card is a glance at.
        assertEquals(3, model.activeCount)
        assertTrue(model.showsCount)
    }

    @Test
    fun `a pull the server sent no title for is flagged rather than drawn blank`() {
        // The bug this guards: the downloads lane's album_title had no reader in main source, and on a
        // device 34 of 35 pulls arrived with nothing in it. A blank line on a home screen looks like a
        // broken widget, so the card draws "Untitled album" instead - see PullWidget.titleLine.
        val model = PullCardModel.of(listOf(WidgetFixtures.untitled()))

        assertTrue(model.hasActivePull)
        // Whitespace in, empty out, so the render site's one check is enough.
        assertEquals("", model.albumTitle)
        assertFalse(model.hasAlbumTitle)
        // Still a real pull with real progress: a missing title is thin metadata, not a failure.
        assertEquals("62%", model.percent)
    }

    @Test
    fun `no pull yields an untrimmed title, and blank and empty never disagree`() {
        val pulls: List<Pull> = listOf(
            WidgetFixtures.downloading(),
            WidgetFixtures.searching(),
            WidgetFixtures.completed(),
            WidgetFixtures.untitled(),
            WidgetFixtures.padded(),
            WidgetFixtures.downloading("   Promises"),
            WidgetFixtures.downloading("Bruise   "),
        )

        pulls.forEach { pull ->
            val model = PullCardModel.of(listOf(pull))

            assertEquals(model.albumTitle, model.albumTitle.trim())
            assertEquals(model.albumTitle.isNotEmpty(), model.hasAlbumTitle)
            assertEquals(model.albumTitle.isNotBlank(), model.hasAlbumTitle)
        }
    }

    @Test
    fun `a padded title reaches the card trimmed`() {
        val model = PullCardModel.of(listOf(WidgetFixtures.padded()))

        assertEquals("Black Classical Music", model.albumTitle)
        assertTrue(model.hasAlbumTitle)
    }
}
