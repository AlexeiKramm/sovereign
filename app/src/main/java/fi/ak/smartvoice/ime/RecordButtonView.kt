package fi.ak.smartvoice.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * A custom animated record button featuring:
 * - A vector-drawn microphone icon when idle
 * - A crisp rounded-square stop icon when recording
 * - A breathing pulsing glow ring expanding around the button while recording is active
 * - Smooth touch scale and tactile haptic feedback
 */
class RecordButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private var isListening = false
    private var isPressedState = false

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var glowProgress = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            glowProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private val idleColor = Color.parseColor("#2563EB") // Royal blue
    private val recordingColor = Color.parseColor("#DC2626") // Vivid crimson

    init {
        isClickable = true
        isFocusable = true
    }

    fun setListening(listening: Boolean) {
        if (isListening == listening) return
        isListening = listening
        if (listening) {
            pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            glowProgress = 0f
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressedState = true
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP -> {
                isPressedState = false
                invalidate()
                performClick()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = dp(34f)
        val maxGlowRadius = dp(46f)

        // 1. Draw outer pulsing glow rings if recording
        if (isListening && glowProgress > 0f) {
            val currentGlowRadius = baseRadius + (maxGlowRadius - baseRadius) * glowProgress
            val alpha = ((1f - glowProgress) * 0.45f * 255).toInt()
            glowPaint.color = recordingColor
            glowPaint.alpha = alpha
            canvas.drawCircle(cx, cy, currentGlowRadius, glowPaint)

            // Secondary subtle inner ring
            val innerProgress = (glowProgress + 0.5f) % 1f
            val innerGlowRadius = baseRadius + (maxGlowRadius - baseRadius) * innerProgress * 0.6f
            val innerAlpha = ((1f - innerProgress) * 0.25f * 255).toInt()
            glowPaint.alpha = innerAlpha
            canvas.drawCircle(cx, cy, innerGlowRadius, glowPaint)
        }

        // 2. Draw center circle button (scales down slightly on press)
        val scale = if (isPressedState) 0.94f else 1.0f
        buttonPaint.color = if (isListening) recordingColor else idleColor
        canvas.drawCircle(cx, cy, baseRadius * scale, buttonPaint)

        // 3. Draw Icon in the center
        if (isListening) {
            // Draw Stop square
            val squareSize = dp(18f) * scale
            val halfSquare = squareSize / 2f
            val cornerRadius = dp(4f) * scale
            val rect = RectF(cx - halfSquare, cy - halfSquare, cx + halfSquare, cy + halfSquare)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, iconFillPaint)
        } else {
            // Draw crisp vector Microphone
            iconPaint.strokeWidth = dp(2.8f) * scale
            iconFillPaint.color = Color.WHITE

            // Mic capsule
            val capWidth = dp(11f) * scale
            val capHeight = dp(19f) * scale
            val capHalfW = capWidth / 2f
            val capTop = cy - dp(13f) * scale
            val capBottom = capTop + capHeight
            val capRadius = capHalfW
            val capRect = RectF(cx - capHalfW, capTop, cx + capHalfW, capBottom)
            canvas.drawRoundRect(capRect, capRadius, capRadius, iconFillPaint)

            // Mic cradle arc
            val cradleRadius = dp(11f) * scale
            val cradleRect = RectF(cx - cradleRadius, cy - dp(7f) * scale, cx + cradleRadius, cy + dp(15f) * scale)
            canvas.drawArc(cradleRect, 0f, 180f, false, iconPaint)

            // Vertical stem
            val stemTop = cy + dp(15f) * scale
            val stemBottom = stemTop + dp(6f) * scale
            canvas.drawLine(cx, stemTop, cx, stemBottom, iconPaint)

            // Horizontal base foot
            val footHalfW = dp(8f) * scale
            canvas.drawLine(cx - footHalfW, stemBottom, cx + footHalfW, stemBottom, iconPaint)
        }
    }
}
