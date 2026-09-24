package com.rek123p.walltimeedition

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.view.RoundedCorner
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

// Fixed target width for the "HH:MM" text, in dp. Independent of screen
// size, so the DVD-style bounce always has room to move. Declared at file
// level - Kotlin does not allow a companion object inside an inner class.
private const val TARGET_TIME_WIDTH_DP = 600f

// Extra buffer (in dp) above the system navigation bar, to clear a launcher's
// bottom dock/icon row. That row is drawn by the launcher itself, on top of
// the wallpaper, and its exact height can't be queried from here (it varies
// by launcher) - this is a generous fixed estimate on top of the real,
// measured navigation bar inset.
private const val DOCK_BUFFER_DP = 110f

// Small horizontal gap (in dp) between the minutes digits and the AM/PM badge.
private const val AM_PM_GAP_DP = 6f

// Extra margin (in dp) added around each corner name's own measured text
// size to build its (invisible) hit-detection rect. Deliberately tiny -
// these are a hidden easter egg, not a UI element, so the reveal should
// only trigger once the clock actually overlaps the name, not from a
// noticeable distance away.
private const val CORNER_HITBOX_PADDING_DP = 0f

// Fallback safe distance (in dp) from the true screen corner, used when the
// precise rounded-corner radius can't be queried (pre-Android 12, or the
// query fails). Deliberately small - just enough to dodge a rounded corner
// / camera cutout on most devices - so the name still reads as sitting IN
// the corner rather than floating well inside it. On Android 12+ this is
// only a floor: onApplyWindowInsets raises it, but only up to the device's
// own real corner radius, never further.
private const val CORNER_SAFE_PADDING_DP = 10f

// Corner text size, in dp - deliberately small/subtle, this is a hidden
// signature, not a headline.
private const val CORNER_TEXT_SIZE_DP = 14f

// How long (ms) a corner's name stays visible after the clock stops overlapping it.
private const val CORNER_VISIBLE_MS = 5000L

// Declared at file level, not inside ClockEngine - Kotlin does not allow a
// nested class (data class included) inside an inner class.
private data class AuthorCorner(
    val label: String,
    val rect: Rect = Rect(),
    var wasOverlapping: Boolean = false,
    var visibleUntil: Long = 0L
)

/**
 * Live wallpaper: a bouncing (DVD-logo style) digital clock, with the date
 * and weekday name shown as one fixed line near the bottom of the screen,
 * and a hidden author signature that reveals itself in whichever corner the
 * clock last passed over.
 *
 * Language and time format (24h / 12h) are read from WallpaperPrefs (set via
 * MainActivity's settings screen) and applied live: a
 * SharedPreferences.OnSharedPreferenceChangeListener picks up changes made
 * while the wallpaper is already running, without needing to reset it.
 *
 * In 12h mode, the AM/PM marker is NOT part of the main "hh:mm" string (that
 * would draw it at full size and blow out the fixed width budget). It's
 * rendered separately, at half the main text's size, tucked right after the
 * minutes digits and sharing their baseline - a small badge at their
 * bottom-right corner, not a third digit-sized block.
 *
 * The clock digits are rendered at a fixed on-screen width
 * (TARGET_TIME_WIDTH_DP) instead of scaling to the screen width - that keeps
 * their size predictable and leaves real room for the bounce, instead of the
 * block nearly spanning the screen edge-to-edge.
 */
class WallTimeWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ClockEngine()

    inner class ClockEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = true

        private var screenWidth = 0
        private var screenHeight = 0

        // Bouncing clock position/velocity
        private var posX = 0f
        private var posY = 0f
        private var velX = 5f
        private var velY = 3.5f

        private var timeBlockWidth = 0f
        private var timeBlockHeight = 0f

        // Cached font metrics for timePaint, refreshed only when its textSize
        // changes (computeTextSizes) - used to keep the draw position and the
        // bounce hitbox height consistent with each other (see both usages).
        private val timeFontMetrics = Paint.FontMetrics()

        // Cached font metrics for cornerPaint, refreshed alongside it in
        // computeTextSizes - used both to anchor each corner's name
        // precisely to that corner's edges (see cornerTextAnchor) and to
        // size its hit-detection rect to the text's own height (layoutCorners).
        private val cornerFontMetrics = Paint.FontMetrics()

        // Real, measured system navigation bar inset (gesture bar / buttons),
        // updated via onApplyWindowInsets (Android 11+). Stays 0 on older
        // versions, where DOCK_BUFFER_DP alone still gives a safe margin.
        private var systemBottomInset = 0

        // Safe distance from the true screen corners - the larger of a fixed
        // fallback and the device's real rounded-corner radius (Android 12+,
        // see onApplyWindowInsets). Has a sane fallback value before insets
        // are first delivered too.
        private var cornerInsetPx = 0f

        // Fixed position for the single date+weekday line, recalculated on layout/text change
        private var dateWeekdayX = 0f
        private var dateWeekdayY = 0f

        // Current settings, loaded from WallpaperPrefs and kept in sync via prefsListener
        private var currentLocale: Locale = Locale.getDefault()
        private var use12Hour = false

        // Same names/order as V2 and the original Locus wallpaper: top-left,
        // top-right, bottom-left, bottom-right.
        private val corners = listOf(
            AuthorCorner("Andrzej Kamiński"),
            AuthorCorner("rekin123p"),
            AuthorCorner("rek123p"),
            AuthorCorner("[-Bravo-]")
        )

        private val backgroundPaint = Paint().apply {
            color = Color.rgb(10, 10, 10) // dark background, matching the screensaver's style
        }

        private val timePaint = Paint().apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            letterSpacing = 0.02f
        }

        // AM/PM badge - half the size of timePaint (set in computeTextSizes),
        // drawn only in 12h mode, right after the minutes digits.
        private val amPmPaint = Paint().apply {
            color = Color.WHITE
            typeface = Typeface.SANS_SERIF
            isAntiAlias = true
        }

        private val subPaint = Paint().apply {
            color = Color.rgb(190, 190, 190)
            typeface = Typeface.SANS_SERIF
            isAntiAlias = true
        }

        private val cornerPaint = Paint().apply {
            color = Color.WHITE
            typeface = Typeface.SANS_SERIF
            isAntiAlias = true
        }

        private var timeText = ""          // digits only, e.g. "14:07" or "02:07" - never includes AM/PM
        private var amPmText = ""          // "AM"/"PM" (or locale equivalent) - empty in 24h mode
        private var dateWeekdayText = ""   // e.g. "Czwartek, 24.09.2026"
        private var lastMinute = -1

        private val drawRunner = Runnable { drawFrame() }

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WallpaperPrefs.KEY_LANGUAGE || key == WallpaperPrefs.KEY_TIME_FORMAT) {
                loadPreferences()
                updateClockText(force = true)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            loadPreferences()
            WallpaperPrefs.prefs(this@WallTimeWallpaperService)
                .registerOnSharedPreferenceChangeListener(prefsListener)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenWidth = width
            // Some surfaces (notably the system's own wallpaper picker/preview
            // screen, which wraps the preview in its own UI chrome) report a
            // height smaller than the true physical display - which made the
            // clock bounce off that reduced height instead of the real bottom
            // edge. Querying the real display size directly sidesteps that.
            screenHeight = getRealScreenHeight(fallback = height)

            // Sane fallback until (if ever) onApplyWindowInsets gives us a
            // precise, device-real rounded-corner radius to compare against.
            if (cornerInsetPx <= 0f) {
                cornerInsetPx = CORNER_SAFE_PADDING_DP * resources.displayMetrics.density
            }

            computeTextSizes(width)
            updateClockText(force = true)
            layoutCorners()
            posX = Random.nextFloat() * (width - timeBlockWidth).coerceAtLeast(1f)
            posY = Random.nextFloat() * (screenHeight - timeBlockHeight).coerceAtLeast(1f)
        }

        /**
         * The real, physical display height in pixels, independent of
         * whatever height the current drawing surface happens to report
         * (which can be smaller - e.g. inside the system's own wallpaper
         * preview screen). Falls back to the surface-reported height if the
         * query fails for any reason.
         */
        private fun getRealScreenHeight(fallback: Int): Int {
            return try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.bounds.height()
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    metrics.heightPixels
                }
            } catch (_: Exception) {
                fallback
            }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)
            // Real system navigation bar height (gesture bar / 3-button nav).
            // Only delivered on Android 11+; stays 0 on older versions, where
            // DOCK_BUFFER_DP alone still gives a safe fallback margin.
            systemBottomInset = insets.getInsets(WindowInsets.Type.systemBars()).bottom

            // Real rounded-corner radius, only queryable on Android 12+
            // (RoundedCorner API). Takes the largest of the four corners and
            // uses it if it's bigger than our fixed fallback - i.e. we only
            // ever grow the safe margin from real device data, never shrink
            // it below the fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val positions = listOf(
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT
                )
                val maxRadius = positions.mapNotNull { insets.getRoundedCorner(it)?.radius }
                    .maxOrNull()?.toFloat() ?: 0f
                val fallback = CORNER_SAFE_PADDING_DP * resources.displayMetrics.density
                cornerInsetPx = maxOf(fallback, maxRadius)
            }

            layoutFixedDateBlock()
            layoutCorners()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                handler.post(drawRunner)
            } else {
                handler.removeCallbacks(drawRunner)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
            WallpaperPrefs.prefs(this@WallTimeWallpaperService)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        }

        /** Reads language + time format from WallpaperPrefs into currentLocale/use12Hour. */
        private fun loadPreferences() {
            val context = this@WallTimeWallpaperService
            currentLocale = WallpaperPrefs.resolveLocale(WallpaperPrefs.getLanguage(context))
            use12Hour = WallpaperPrefs.getTimeFormat(context) == WallpaperPrefs.TIME_FORMAT_12H
        }

        /**
         * Sizes the clock digits to a fixed target width (converted from dp
         * to px using the device's density), not a percentage of the
         * screen. On a screen narrower than the target (rare - small phones
         * in landscape, etc.) it's capped down so it still fits.
         *
         * Always measured against "88:88" - the AM/PM badge is sized and
         * positioned separately (see drawFrame/measureTimeBlock) and isn't
         * part of this target, so it no longer widens the whole block the
         * way folding it into one "hh:mm a" string used to.
         *
         * Also fixes timeBlockHeight here, once, from timePaint's font
         * metrics (ascent/descent) - not from a text's ink bounds. Font
         * metrics are a property of the font+textSize only, independent of
         * which digits are actually on screen, so this doesn't jitter
         * between minutes the way getTextBounds() on the live text did.
         *
         * Crucially, this also matches how drawFrame positions the text
         * (posY - ascent as the baseline): using the same ascent/descent for
         * both the draw offset and the hitbox height keeps them consistent,
         * so the bounce edges line up with what's actually drawn.
         */
        private fun computeTextSizes(width: Int) {
            val density = resources.displayMetrics.density
            val targetWidthPx = (TARGET_TIME_WIDTH_DP * density).coerceAtMost(width * 0.92f)

            val referenceSize = 100f
            timePaint.textSize = referenceSize
            val measuredWidth = timePaint.measureText("88:88")
            val scale = targetWidthPx / measuredWidth

            timePaint.textSize = (referenceSize * scale).coerceIn(36f, 220f)
            subPaint.textSize = (timePaint.textSize * 0.3f).coerceIn(20f, 48f)
            amPmPaint.textSize = timePaint.textSize * 0.5f
            cornerPaint.textSize = CORNER_TEXT_SIZE_DP * density

            timePaint.getFontMetrics(timeFontMetrics)
            // ascent is negative (distance above the baseline); descent is positive
            timeBlockHeight = (timeFontMetrics.descent - timeFontMetrics.ascent) + 8f

            cornerPaint.getFontMetrics(cornerFontMetrics)
        }

        /**
         * Recomputes only the width, every minute - the height stays fixed
         * (set once in computeTextSizes) so it doesn't jitter between the
         * different digit shapes of each new time.
         *
         * getTextBounds() would also under-measure the width here, since it
         * ignores letterSpacing (non-zero on timePaint) - measureText()
         * correctly includes it in the returned advance width.
         *
         * Includes the AM/PM badge's width (plus its gap) when in 12h mode,
         * so the bounce hitbox still covers everything actually drawn.
         */
        private fun measureTimeBlock() {
            timeBlockWidth = timePaint.measureText(timeText) + 8f
            if (use12Hour && amPmText.isNotEmpty()) {
                val density = resources.displayMetrics.density
                timeBlockWidth += (AM_PM_GAP_DP * density) + amPmPaint.measureText(amPmText)
            }
        }

        /**
         * Centers the single date+weekday line, fixed above the bottom edge
         * by the real system navigation bar inset plus a fixed dock buffer.
         * The flying clock is free to pass over this row - it's drawn on top
         * of it (see drawFrame), so there's no need to fence it off.
         */
        private fun layoutFixedDateBlock() {
            if (screenWidth == 0 || screenHeight == 0) return

            val density = resources.displayMetrics.density
            val bottomMarginPx = systemBottomInset + (DOCK_BUFFER_DP * density)

            val bounds = Rect()
            subPaint.getTextBounds(dateWeekdayText, 0, dateWeekdayText.length, bounds)

            dateWeekdayY = screenHeight - bottomMarginPx
            dateWeekdayX = (screenWidth - bounds.width()) / 2f
        }

        /**
         * Positions the 4 corner hit-detection rects, sized to each name's
         * own measured text (plus CORNER_HITBOX_PADDING_DP of margin) rather
         * than one fixed zone shared by all four - "rek123p" and "Andrzej
         * Kamiński" are very different widths, and a shared box was either
         * too tight for the long name or way too loose for the short ones.
         *
         * Inset from the true screen edges by cornerInsetPx on all four
         * sides, top AND bottom alike - these are a hidden easter egg, not
         * a persistent UI row, so unlike the fixed date line they don't need
         * extra clearance from a launcher's dock/nav bar; only enough margin
         * to dodge rounded corners / camera cutouts (see cornerInsetPx).
         */
        private fun layoutCorners() {
            if (screenWidth == 0 || screenHeight == 0) return

            val density = resources.displayMetrics.density
            val padding = CORNER_HITBOX_PADDING_DP * density
            val sideInset = cornerInsetPx
            val topInset = cornerInsetPx
            val bottomInset = cornerInsetPx
            val textHeight = (cornerFontMetrics.descent - cornerFontMetrics.ascent) + padding * 2

            fun widthFor(label: String) = cornerPaint.measureText(label) + padding * 2

            val w0 = widthFor(corners[0].label)
            corners[0].rect.set(
                sideInset.toInt(), topInset.toInt(),
                (sideInset + w0).toInt(), (topInset + textHeight).toInt()
            )
            val w1 = widthFor(corners[1].label)
            corners[1].rect.set(
                (screenWidth - sideInset - w1).toInt(), topInset.toInt(),
                (screenWidth - sideInset).toInt(), (topInset + textHeight).toInt()
            )
            val w2 = widthFor(corners[2].label)
            corners[2].rect.set(
                sideInset.toInt(), (screenHeight - bottomInset - textHeight).toInt(),
                (sideInset + w2).toInt(), (screenHeight - bottomInset).toInt()
            )
            val w3 = widthFor(corners[3].label)
            corners[3].rect.set(
                (screenWidth - sideInset - w3).toInt(), (screenHeight - bottomInset - textHeight).toInt(),
                (screenWidth - sideInset).toInt(), (screenHeight - bottomInset).toInt()
            )
        }

        /**
         * The exact draw position + text alignment for one corner's name,
         * anchored to that corner's real edges (not centered in the larger
         * hit-detection zone): left-aligned hugging the left edge for the
         * two left corners, right-aligned hugging the right edge for the
         * two right corners; top corners' text-top touches topInset, bottom
         * corners' text-bottom touches the bottom safe line. Index order
         * matches `corners`: 0 = top-left, 1 = top-right, 2 = bottom-left,
         * 3 = bottom-right.
         */
        private fun cornerTextAnchor(index: Int): Triple<Float, Float, Paint.Align> {
            val sideInset = cornerInsetPx
            val topInset = cornerInsetPx
            val bottomInset = cornerInsetPx

            return when (index) {
                0 -> Triple(sideInset, topInset - cornerFontMetrics.ascent, Paint.Align.LEFT)
                1 -> Triple(screenWidth - sideInset, topInset - cornerFontMetrics.ascent, Paint.Align.RIGHT)
                2 -> Triple(sideInset, screenHeight - bottomInset - cornerFontMetrics.descent, Paint.Align.LEFT)
                else -> Triple(
                    screenWidth - sideInset,
                    screenHeight - bottomInset - cornerFontMetrics.descent,
                    Paint.Align.RIGHT
                )
            }
        }

        private fun updateClockText(force: Boolean = false) {
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.MINUTE)
            if (!force && minute == lastMinute) return
            lastMinute = minute

            val hourPattern = if (use12Hour) "hh:mm" else "HH:mm"
            timeText = SimpleDateFormat(hourPattern, currentLocale).format(now.time)
            amPmText = if (use12Hour) {
                SimpleDateFormat("a", currentLocale).format(now.time)
            } else {
                ""
            }

            val dateText = SimpleDateFormat("dd.MM.yyyy", currentLocale).format(now.time)
            val weekdayText = SimpleDateFormat("EEEE", currentLocale).format(now.time)
                .replaceFirstChar { it.uppercase() }
            dateWeekdayText = "$weekdayText, $dateText"

            measureTimeBlock()
            layoutFixedDateBlock()
        }

        /**
         * Checks the clock's current bounding box against each corner zone,
         * and marks a corner to show its name for CORNER_VISIBLE_MS once the
         * clock has passed over it and left again - same "reveal on exit"
         * behavior as V2 and the original Locus wallpaper.
         */
        private fun updateCornerVisibility() {
            val clockRect = Rect(
                posX.toInt(), posY.toInt(),
                (posX + timeBlockWidth).toInt(), (posY + timeBlockHeight).toInt()
            )
            val now = System.currentTimeMillis()
            for (corner in corners) {
                val isOverlapping = Rect.intersects(clockRect, corner.rect)
                if (corner.wasOverlapping && !isOverlapping) {
                    corner.visibleUntil = now + CORNER_VISIBLE_MS
                }
                corner.wasOverlapping = isOverlapping
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    updateClockText()
                    updatePosition()
                    updateCornerVisibility()

                    canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)

                    // Fixed date+weekday line at the bottom, independent of the clock's position.
                    canvas.drawText(dateWeekdayText, dateWeekdayX, dateWeekdayY, subPaint)

                    // Flying clock
                    val timeY = posY - timeFontMetrics.ascent
                    canvas.drawText(timeText, posX, timeY, timePaint)

                    // AM/PM badge - half size, sharing the digits' baseline (bottom),
                    // tucked right after them (right) - i.e. their bottom-right corner.
                    if (use12Hour && amPmText.isNotEmpty()) {
                        val density = resources.displayMetrics.density
                        val amPmX = posX + timePaint.measureText(timeText) + (AM_PM_GAP_DP * density)
                        canvas.drawText(amPmText, amPmX, timeY, amPmPaint)
                    }

                    // Corner signatures - drawn last, topmost, only while revealed.
                    // Anchored directly to each corner's edges (cornerTextAnchor),
                    // not centered inside the (larger, invisible) hit-detection zone.
                    val now = System.currentTimeMillis()
                    for ((index, corner) in corners.withIndex()) {
                        if (now < corner.visibleUntil) {
                            val (anchorX, anchorY, align) = cornerTextAnchor(index)
                            cornerPaint.textAlign = align
                            canvas.drawText(corner.label, anchorX, anchorY, cornerPaint)
                        }
                    }
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            handler.removeCallbacks(drawRunner)
            if (visible) {
                handler.postDelayed(drawRunner, 28) // ~35 FPS, light on weaker hardware
            }
        }

        private fun updatePosition() {
            posX += velX
            posY += velY

            if (posX <= 0 || posX + timeBlockWidth >= screenWidth) {
                velX *= -1
                posX = posX.coerceIn(0f, (screenWidth - timeBlockWidth).coerceAtLeast(0f))
            }
            if (posY <= 0 || posY + timeBlockHeight >= screenHeight) {
                velY *= -1
                posY = posY.coerceIn(0f, (screenHeight - timeBlockHeight).coerceAtLeast(0f))
            }
        }
    }
}