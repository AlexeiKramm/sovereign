package io.github.alexeikramm.sovereign.floating

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The overlay window's root view. It exists for one reason: to see the pointer's **screen**
 * position.
 *
 * `ViewGroup` dispatches a touch to its children first, and only calls its own `OnTouchListener`
 * when no child consumes it — Compose consumes the pill's own touches, so the listener sees only
 * `ACTION_OUTSIDE`. `dispatchTouchEvent`, by contrast, always runs, so the raw coordinates are
 * captured here before Compose gets the event. `MotionEvent.rawX/rawY` are measured against the
 * display and do not collapse as the window moves under the finger, which is what
 * [WindowDrag] needs.
 */
class PillRootView(context: Context) : FrameLayout(context) {

    /** Called with the pointer's raw screen position for every event except `ACTION_OUTSIDE`. */
    var onPointerScreen: ((Float, Float) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_OUTSIDE) {
            onPointerScreen?.invoke(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }
}
