package fi.ak.smartvoice.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drag correction. On the device the pill travelled ~half the finger's distance because every
 * pointer delta is window-local and the window moves under the finger; anchoring to the pointer's
 * screen position removes the feedback entirely, because screen coordinates do not collapse as the
 * window moves. These tests pin the arithmetic.
 */
class WindowDragTest {

    @Test
    fun `a finger that has not moved leaves the offset alone`() {
        val drag = WindowDrag()
        drag.begin(screen = PillOffset(700f, 1600f), offset = PillOffset(300f, 500f))

        val offset = drag.offsetFor(screenX = 700f, screenY = 1600f)

        assertEquals(300f, offset.x, 0.001f)
        assertEquals(500f, offset.y, 0.001f)
    }

    @Test
    fun `dragging left and up increases the offset by the screen travel`() {
        val drag = WindowDrag()
        drag.begin(screen = PillOffset(700f, 1600f), offset = PillOffset(300f, 500f))

        val offset = drag.offsetFor(screenX = 200f, screenY = 1200f)

        // 500 px left and 400 px up => the offset from the BOTTOM|END corner grows by both.
        assertEquals(800f, offset.x, 0.001f)
        assertEquals(900f, offset.y, 0.001f)
    }

    @Test
    fun `the window tracks the finger when it reverses`() {
        val drag = WindowDrag()
        drag.begin(screen = PillOffset(700f, 1600f), offset = PillOffset(300f, 500f))

        val out = drag.offsetFor(screenX = 400f, screenY = 1500f)
        val back = drag.offsetFor(screenX = 900f, screenY = 1700f)

        assertEquals(600f, out.x, 0.001f)
        assertEquals(100f, back.x, 0.001f)
        assertEquals(400f, back.y, 0.001f)
    }

    @Test
    fun `a new drag does not inherit the previous gesture's anchor`() {
        val drag = WindowDrag()
        drag.begin(screen = PillOffset(700f, 1600f), offset = PillOffset(300f, 500f))
        drag.offsetFor(screenX = 200f, screenY = 1200f)

        drag.begin(screen = PillOffset(100f, 2000f), offset = PillOffset(50f, 60f))
        val offset = drag.offsetFor(screenX = 90f, screenY = 1990f)

        assertEquals(60f, offset.x, 0.001f)
        assertEquals(70f, offset.y, 0.001f)
    }
}
