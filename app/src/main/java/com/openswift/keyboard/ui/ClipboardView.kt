package com.openswift.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.openswift.keyboard.R
import com.openswift.keyboard.data.ClipboardHistory
import kotlin.math.abs
import kotlin.math.max

/**
 * High-performance, ultra-smooth Clipboard View with:
 * 1. Pinned items at the TOP for immediate zero-scroll access!
 * 2. Instant O(1) text truncation caching (zero per-frame string chopping).
 * 3. Viewport culling (only rendering visible rows).
 * 4. Physics-based smooth inertial fling scrolling via OverScroller and VelocityTracker.
 * 5. Full dialog actions: Paste, Pin/Unpin, Delete, Clear unpinned.
 */
class ClipboardView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var onItemSelected: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onReturnToKeyboard: (() -> Unit)? = null

    var clipboard = ClipboardHistory(ctx)

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity
    private val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    var isDeleteMode = false
        set(value) {
            field = value
            invalidate()
        }

    var keyHeightDp = 78
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var explicitHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    // Modal Action Dialog for long-press
    private var activeDialogCard: ClipCard? = null
    private val dialogRect = RectF()
    private val dialogPasteBounds = RectF()
    private val dialogPinBounds = RectF()
    private val dialogDeleteBounds = RectF()
    private val dialogCancelBounds = RectF()

    // Paints
    private val bgPaint = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val scrimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val dialogBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B2028.toInt()
        style = Paint.Style.FILL
    }
    private val dialogBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * density
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }
    private val abcBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        color = 0xFF19D1F4.toInt() // Cyan/Teal
        isFakeBoldText = true
    }
    private val abcPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B2028.toInt()
        style = Paint.Style.FILL
    }
    private val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 14f * density
        color = 0xFF969DA9.toInt()
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4F58.toInt()
        style = Paint.Style.FILL
    }
    private val cardDeleteModeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3F2024.toInt()
        style = Paint.Style.FILL
    }
    private val cardDeleteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88EF4444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val cardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt()
        style = Paint.Style.FILL
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.LEFT
    }
    private val swipeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDC2626.toInt()
        style = Paint.Style.FILL
    }
    private val emptySubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        color = 0xFF8A909D.toInt()
    }
    private val dialogButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2A313E.toInt()
    }
    private val dialogDeleteBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF5C1C24.toInt()
    }
    private val dialogTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14.5f * density
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }

    // Drawables
    private val hideIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_keyboard_arrow_down)?.mutate()?.apply {
        setTint(0xFFFFFFFF.toInt())
    }
    private val trashIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_delete)?.mutate()?.apply {
        setTint(0xFFFFFFFF.toInt())
    }
    private val closeIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_close)?.mutate()?.apply {
        setTint(0xFFFFFFFF.toInt())
    }

    // Touch targets
    private val abcBounds = RectF()
    private val trashPillBounds = RectF()
    private val clearAllBounds = RectF()
    private val hideBounds = RectF()

    // Internal model for layout items
    private data class ClipCard(
        val text: String,
        val isPinned: Boolean,
        val bounds: RectF
    )

    private val layoutCards = ArrayList<ClipCard>()

    // Text truncation cache to prevent UI lag on scroll
    private val textTruncateCache = HashMap<String, String>()

    // Swipe to delete state
    private var swipedCard: ClipCard? = null
    private var isSwiping = false
    private var swipeOffsetX = 0f

    // Dimensions
    private val headerHeight = 48f * density
    private val horizontalMargin = 12f * density
    private val cardHeight = 44f * density
    private val cardRadius = 8f * density
    private val gridGap = 8f * density

    // Scroll state and smooth fling
    private var scrollYOffset = 0f
    private var maxScroll = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var isDragging = false
    private val scroller = OverScroller(ctx)
    private var velocityTracker: VelocityTracker? = null

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollYOffset = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    // Long press detection for deleting
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pressedCard: ClipCard? = null
    private var longPressTriggered = false
    private var ignoreNextUpForDialog = false
    private val longPressRunnable = Runnable {
        pressedCard?.let { card ->
            longPressTriggered = true
            ignoreNextUpForDialog = true
            vibrateFeedback()
            activeDialogCard = card
            invalidate()
        }
    }

    fun resetScroll() {
        longPressHandler.removeCallbacksAndMessages(null)
        scroller.forceFinished(true)
        scrollYOffset = 0f
        isDragging = false
        isSwiping = false
        swipeOffsetX = 0f
        activeDialogCard = null
        pressedCard = null
        swipedCard = null
    }

    fun refresh(resetScrollPosition: Boolean = true) {
        if (resetScrollPosition) {
            resetScroll()
        }
        clipboard.invalidateCache()
        try {
            clipboard.captureSystem(context, enabled = true, privateField = false)
        } catch (_: Exception) {}
        textTruncateCache.clear()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetScroll()
        refresh(resetScrollPosition = false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        longPressHandler.removeCallbacksAndMessages(null)
        resetScroll()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            resetScroll()
            refresh(resetScrollPosition = false)
        } else {
            longPressHandler.removeCallbacksAndMessages(null)
            resetScroll()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val targetHeight = if (explicitHeightPx > 0) {
            explicitHeightPx
        } else {
            val keyHeightPx = keyHeightDp * density
            ((4 * keyHeightPx) + (2f * density * 3) + (keyHeightPx * 0.82f) + (8f * density)).toInt()
        }
        setMeasuredDimension(w, targetHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Fill pitch-black background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Draw Header
        drawHeader(canvas, w)

        // 3. Draw Scrollable Content (العناصر المثبتة في البداية + ثم الأحدث)
        canvas.save()
        canvas.clipRect(0f, headerHeight, w, h)

        layoutCards.clear()

        val pinnedItems = clipboard.pinnedItems()
        val recentItems = clipboard.items()
        val pinnedSet = clipboard.pinnedSet()

        if (recentItems.isEmpty() && pinnedItems.isEmpty()) {
            val emptyCenterY = (h - headerHeight) / 2f + headerHeight
            canvas.drawText("الحافظة فارغة", w / 2f, emptyCenterY - (10f * density), headerTitlePaint)
            canvas.drawText("قم بنسخ أي نص وسيظهر هنا تلقائياً", w / 2f, emptyCenterY + (16f * density), emptySubPaint)
            canvas.restore()
            if (activeDialogCard != null) drawActionDialog(canvas, w, h)
            return
        }

        val totalAvailableWidth = w - (horizontalMargin * 2f) - gridGap
        val colWidth = totalAvailableWidth / 2f
        var curY = headerHeight + (10f * density)

        // Section 1: الأحدث (Recent items) - Shown first
        if (recentItems.isNotEmpty()) {
            val sectionY = curY - scrollYOffset
            if (sectionY + (24f * density) >= headerHeight && sectionY <= h) {
                canvas.drawText("الأحدث", w - horizontalMargin, sectionY + (14f * density), sectionHeaderPaint)
            }
            curY += 24f * density

            for (i in recentItems.indices step 2) {
                val rightText = recentItems[i]
                val leftText = recentItems.getOrNull(i + 1)
                curY = recordAndDrawRow(canvas, w, curY, colWidth, rightText, leftText, false, h)
                curY += gridGap
            }
            curY += 10f * density
        }

        // Section 2: العناصر المثبَّتة (Pinned items) - Shown below
        if (pinnedItems.isNotEmpty()) {
            val sectionY = curY - scrollYOffset
            if (sectionY + (24f * density) >= headerHeight && sectionY <= h) {
                canvas.drawText("العناصر المثبَّتة 📌", w - horizontalMargin, sectionY + (14f * density), sectionHeaderPaint)
            }
            curY += 24f * density

            for (i in pinnedItems.indices step 2) {
                val rightText = pinnedItems[i]
                val leftText = pinnedItems.getOrNull(i + 1)
                curY = recordAndDrawRow(canvas, w, curY, colWidth, rightText, leftText, true, h)
                curY += gridGap
            }
        }

        val totalContentHeight = curY - headerHeight + (12f * density)
        maxScroll = max(0f, totalContentHeight - (h - headerHeight))

        canvas.restore()

        // 4. If Action Dialog is open, draw overlay modal
        if (activeDialogCard != null) {
            drawActionDialog(canvas, w, h)
        }
    }

    private fun recordAndDrawRow(
        canvas: Canvas,
        w: Float,
        contentY: Float,
        colWidth: Float,
        rightText: String?,
        leftText: String?,
        isPinned: Boolean,
        viewHeight: Float
    ): Float {
        val rowCardHeight = cardHeight

        // Right column card (in RTL: right side comes first)
        if (!rightText.isNullOrBlank()) {
            val rightLeft = w - horizontalMargin - colWidth
            val rect = RectF(rightLeft, contentY, rightLeft + colWidth, contentY + rowCardHeight)
            layoutCards.add(ClipCard(rightText, isPinned, rect))

            val drawY = contentY - scrollYOffset
            if (drawY + rowCardHeight >= headerHeight && drawY <= viewHeight) {
                val drawRect = RectF(rightLeft, drawY, rightLeft + colWidth, drawY + rowCardHeight)
                drawCard(canvas, drawRect, rightText, isPinned)
            }
        }

        // Left column card
        if (!leftText.isNullOrBlank()) {
            val rect = RectF(horizontalMargin, contentY, horizontalMargin + colWidth, contentY + rowCardHeight)
            layoutCards.add(ClipCard(leftText, isPinned, rect))

            val drawY = contentY - scrollYOffset
            if (drawY + rowCardHeight >= headerHeight && drawY <= viewHeight) {
                val drawRect = RectF(horizontalMargin, drawY, horizontalMargin + colWidth, drawY + rowCardHeight)
                drawCard(canvas, drawRect, leftText, isPinned)
            }
        }

        return contentY + rowCardHeight
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        // [ ABC ] button on left
        val abcWidth = 56f * density
        val abcHeight = 32f * density
        val abcTop = (headerHeight - abcHeight) / 2f
        abcBounds.set(horizontalMargin, abcTop, horizontalMargin + abcWidth, abcTop + abcHeight)
        canvas.drawRoundRect(abcBounds, 6f * density, 6f * density, abcPillPaint)

        val abcTextY = abcBounds.centerY() - ((abcBtnPaint.ascent() + abcBtnPaint.descent()) / 2f)
        abcBtnPaint.color = 0xFF19D1F4.toInt()
        canvas.drawText("ABC", abcBounds.centerX(), abcTextY, abcBtnPaint)

        // Center Title
        val titleY = (headerHeight / 2f) - ((headerTitlePaint.ascent() + headerTitlePaint.descent()) / 2f)
        headerTitlePaint.textSize = 17f * density
        headerTitlePaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText("حافظة النصوص", w / 2f, titleY, headerTitlePaint)

        // Right side: [ ∨ ] hide button on the far right (enlarged for easy tapping)
        val iconSize = (34f * density).toInt()
        val hideRight = (w - horizontalMargin).toInt()
        val hideLeft = hideRight - iconSize
        val iconTop = ((headerHeight - iconSize) / 2f).toInt()
        val iconBottom = iconTop + iconSize
        hideBounds.set(hideLeft.toFloat() - (8f * density), 0f, w, headerHeight)

        hideIcon?.setBounds(hideLeft, iconTop, hideRight, iconBottom)
        hideIcon?.draw(canvas)

        // Prominent [ 🗑️ مسح ] button (clears unpinned items, keeps pinned safe)
        val trashBtnWidth = 62f * density
        val trashBtnHeight = 32f * density
        val trashTop = (headerHeight - trashBtnHeight) / 2f
        val trashRight = hideLeft - (10f * density)
        val trashLeft = trashRight - trashBtnWidth
        trashPillBounds.set(trashLeft, trashTop, trashRight, trashTop + trashBtnHeight)

        canvas.drawRoundRect(trashPillBounds, 6f * density, 6f * density, abcPillPaint)

        val tIconSize = (16f * density).toInt()
        val tIconLeft = (trashLeft + (7f * density)).toInt()
        val tIconTop = (trashPillBounds.centerY() - (tIconSize / 2f)).toInt()
        trashIcon?.setTint(0xFFE5E7EB.toInt())
        trashIcon?.setBounds(tIconLeft, tIconTop, tIconLeft + tIconSize, tIconTop + tIconSize)
        trashIcon?.draw(canvas)

        val tTextX = trashPillBounds.right - (15f * density)
        val tTextY = trashPillBounds.centerY() - ((cardTextPaint.ascent() + cardTextPaint.descent()) / 2f)
        cardTextPaint.textSize = 13f * density
        canvas.drawText("مسح", tTextX, tTextY, cardTextPaint)
        cardTextPaint.textSize = 15f * density

        clearAllBounds.setEmpty()
    }

    private fun drawCard(canvas: Canvas, rect: RectF, text: String, isPinned: Boolean) {
        val isBeingSwiped = isSwiping && swipedCard?.text == text && swipedCard?.isPinned == isPinned && swipeOffsetX < 0f

        if (isBeingSwiped) {
            canvas.drawRoundRect(rect, cardRadius, cardRadius, swipeBgPaint)

            val iconSize = (18f * density).toInt()
            val iconRight = (rect.right - 10f * density).toInt()
            val iconLeft = iconRight - iconSize
            val iconTop = (rect.centerY() - iconSize / 2f).toInt()
            trashIcon?.setTint(0xFFFFFFFF.toInt())
            trashIcon?.setBounds(iconLeft, iconTop, iconRight, iconTop + iconSize)
            trashIcon?.draw(canvas)

            canvas.save()
            canvas.translate(swipeOffsetX, 0f)
        }

        val paintToUse = if (isDeleteMode) cardDeleteModeBgPaint else cardBgPaint
        canvas.drawRoundRect(rect, cardRadius, cardRadius, paintToUse)
        if (isDeleteMode) {
            canvas.drawRoundRect(rect, cardRadius, cardRadius, cardDeleteBorderPaint)
        }

        val textY = rect.centerY() - ((cardTextPaint.ascent() + cardTextPaint.descent()) / 2f)
        val maxTextWidth = rect.width() - (if (isDeleteMode) 34f * density else 16f * density)
        val clippedText = getCachedTruncatedText(text, maxTextWidth)
        canvas.drawText(clippedText, rect.centerX(), textY, cardTextPaint)

        // Draw pin indicator for pinned items
        if (isPinned) {
            canvas.drawText("📌", rect.left + (6f * density), rect.top + (14f * density), pinPaint)
        }

        // In delete mode, draw a red ✕ badge at the top-left of the card for unpinned items ONLY
        if (isDeleteMode && !isPinned) {
            val badgeRadius = 9f * density
            val badgeCenterX = rect.left + badgeRadius + (4f * density)
            val badgeCenterY = rect.top + badgeRadius + (4f * density)
            canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgeBgPaint)

            val xSize = (12f * density).toInt()
            val xLeft = (badgeCenterX - xSize / 2f).toInt()
            val xTop = (badgeCenterY - xSize / 2f).toInt()
            closeIcon?.setBounds(xLeft, xTop, xLeft + xSize, xTop + xSize)
            closeIcon?.draw(canvas)
        }

        if (isBeingSwiped) {
            canvas.restore()
        }
    }

    private fun drawActionDialog(canvas: Canvas, w: Float, h: Float) {
        val card = activeDialogCard ?: return
        val item = card.text
        val isPinned = card.isPinned

        // Scrim
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // Dialog dimensions
        val dialogWidth = (w - (36f * density)).coerceAtMost(380f * density)
        val dialogHeight = 220f * density
        val dLeft = (w - dialogWidth) / 2f
        val dTop = (h - dialogHeight) / 2f
        dialogRect.set(dLeft, dTop, dLeft + dialogWidth, dTop + dialogHeight)

        // Draw Dialog Background
        canvas.drawRoundRect(dialogRect, 14f * density, 14f * density, dialogBgPaint)
        canvas.drawRoundRect(dialogRect, 14f * density, 14f * density, dialogBorderPaint)

        // Title preview
        val titleText = getCachedTruncatedText(item, dialogWidth - (32f * density))
        val titleY = dTop + (26f * density)
        headerTitlePaint.textSize = 15f * density
        headerTitlePaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(titleText, dialogRect.centerX(), titleY, headerTitlePaint)

        // Buttons
        val btnHeight = 36f * density
        val btnMarginH = 16f * density
        val btnWidth = dialogWidth - (btnMarginH * 2f)

        // Button 1: Paste [ لصق النص في المحادثة ]
        val b1Top = dTop + (42f * density)
        dialogPasteBounds.set(dLeft + btnMarginH, b1Top, dLeft + btnMarginH + btnWidth, b1Top + btnHeight)
        canvas.drawRoundRect(dialogPasteBounds, 8f * density, 8f * density, dialogButtonPaint)
        dialogTextPaint.color = 0xFF2DD4BF.toInt()
        val b1TextY = dialogPasteBounds.centerY() - ((dialogTextPaint.ascent() + dialogTextPaint.descent()) / 2f)
        canvas.drawText("📋 لصق النص في المحادثة", dialogPasteBounds.centerX(), b1TextY, dialogTextPaint)

        // Button 2: Pin / Unpin
        val pinLabel = if (isPinned) "📌 إلغاء التثبيت" else "📌 تثبيت في أعلى الحافظة (حماية من الحذف)"
        val b2Top = b1Top + btnHeight + (8f * density)
        dialogPinBounds.set(dLeft + btnMarginH, b2Top, dLeft + btnMarginH + btnWidth, b2Top + btnHeight)
        canvas.drawRoundRect(dialogPinBounds, 8f * density, 8f * density, dialogButtonPaint)
        dialogTextPaint.color = 0xFFFFFFFF.toInt()
        val b2TextY = dialogPinBounds.centerY() - ((dialogTextPaint.ascent() + dialogTextPaint.descent()) / 2f)
        canvas.drawText(pinLabel, dialogPinBounds.centerX(), b2TextY, dialogTextPaint)

        // Button 3: Delete
        val b3Top = b2Top + btnHeight + (8f * density)
        dialogDeleteBounds.set(dLeft + btnMarginH, b3Top, dLeft + btnMarginH + btnWidth, b3Top + btnHeight)
        if (isPinned) {
            val dialogLockedBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFF2A2E37.toInt()
            }
            canvas.drawRoundRect(dialogDeleteBounds, 8f * density, 8f * density, dialogLockedBtnPaint)
            dialogTextPaint.color = 0xFF9CA3AF.toInt()
            val b3TextY = dialogDeleteBounds.centerY() - ((dialogTextPaint.ascent() + dialogTextPaint.descent()) / 2f)
            canvas.drawText("🔒 نص مثبت (قم بإلغاء التثبيت أولاً لحذفه)", dialogDeleteBounds.centerX(), b3TextY, dialogTextPaint)
        } else {
            canvas.drawRoundRect(dialogDeleteBounds, 8f * density, 8f * density, dialogDeleteBtnPaint)
            dialogTextPaint.color = 0xFFF87171.toInt()
            val b3TextY = dialogDeleteBounds.centerY() - ((dialogTextPaint.ascent() + dialogTextPaint.descent()) / 2f)
            canvas.drawText("🗑️ حذف من الحافظة", dialogDeleteBounds.centerX(), b3TextY, dialogTextPaint)
        }

        // Button 4: Cancel [ إلغاء ]
        val b4Top = b3Top + btnHeight + (8f * density)
        dialogCancelBounds.set(dLeft + btnMarginH, b4Top, dLeft + btnMarginH + btnWidth, b4Top + (28f * density))
        dialogTextPaint.color = 0xFF9CA3AF.toInt()
        val b4TextY = dialogCancelBounds.centerY() - ((dialogTextPaint.ascent() + dialogTextPaint.descent()) / 2f)
        canvas.drawText("إلغاء", dialogCancelBounds.centerX(), b4TextY, dialogTextPaint)
    }

    private fun getCachedTruncatedText(text: String, maxWidth: Float): String {
        val cacheKey = "$text|$maxWidth"
        textTruncateCache[cacheKey]?.let { return it }

        if (cardTextPaint.measureText(text) <= maxWidth) {
            textTruncateCache[cacheKey] = text
            return text
        }

        // Fast binary search for truncation boundary instead of linear dropLast
        var low = 0
        var high = text.length
        var best = 0
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = text.substring(0, mid) + "…"
            if (cardTextPaint.measureText(candidate) <= maxWidth) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val result = if (best > 0) text.substring(0, best) + "…" else text.take(3)
        textTruncateCache[cacheKey] = result
        return result
    }

    fun deleteItem(item: String): Boolean {
        clipboard.remove(item, context)
        textTruncateCache.clear()
        invalidate()
        return true
    }

    private fun vibrateFeedback() {
        try {
            if (!com.openswift.keyboard.data.Settings(context).hapticFeedback) return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    private fun findCardAt(x: Float, y: Float): ClipCard? {
        val contentY = y + scrollYOffset
        for (card in layoutCards) {
            if (card.bounds.contains(x, contentY)) {
                return card
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        // If Action Dialog is visible, handle dialog clicks
        if (activeDialogCard != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (ignoreNextUpForDialog) {
                    ignoreNextUpForDialog = false
                    return true
                }
                val card = activeDialogCard!!
                val item = card.text
                val x = event.x
                val y = event.y

                if (dialogPasteBounds.contains(x, y)) {
                    vibrateFeedback()
                    resetScroll()
                    activeDialogCard = null
                    onItemSelected?.invoke(item)
                    invalidate()
                    return true
                } else if (dialogPinBounds.contains(x, y)) {
                    vibrateFeedback()
                    if (card.isPinned) {
                        clipboard.removePinned(item)
                        clipboard.add(item)
                        Toast.makeText(context, "تم إلغاء التثبيت (يمكنك حذفه الآن) ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.pin(item)
                        clipboard.remove(item, context)
                        Toast.makeText(context, "تم تثبيت النص في أعلى الحافظة (محمي من الحذف) 📌", Toast.LENGTH_SHORT).show()
                    }
                    textTruncateCache.clear()
                    activeDialogCard = null
                    invalidate()
                    return true
                } else if (dialogDeleteBounds.contains(x, y)) {
                    vibrateFeedback()
                    if (card.isPinned) {
                        Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.remove(item, context)
                        textTruncateCache.clear()
                        Toast.makeText(context, "تم حذف النص من الحافظة", Toast.LENGTH_SHORT).show()
                        activeDialogCard = null
                        invalidate()
                    }
                    return true
                } else if (dialogCancelBounds.contains(x, y) || !dialogRect.contains(x, y)) {
                    activeDialogCard = null
                    invalidate()
                    return true
                }
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                ignoreNextUpForDialog = false
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x
                downY = event.y
                lastY = event.y
                isDragging = false
                isSwiping = false
                swipeOffsetX = 0f
                longPressTriggered = false
                pressedCard = null
                swipedCard = null

                // If touched below the header, find if a card was hit
                if (event.y >= headerHeight) {
                    val card = findCardAt(event.x, event.y)
                    if (card != null) {
                        pressedCard = card
                        swipedCard = card
                        longPressHandler.postDelayed(longPressRunnable, 350L)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val totalDy = event.y - downY
                val dy = event.y - lastY

                if (!isDragging && !isSwiping) {
                    // Check if swiping right-to-left on an unpinned card
                    if (dx < -touchSlop && abs(dx) > abs(totalDy) * 1.1f && swipedCard != null) {
                        if (!swipedCard!!.isPinned) {
                            isSwiping = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                        } else {
                            swipedCard = null
                        }
                    } else if (abs(totalDy) > touchSlop) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        swipedCard = null
                        pressedCard = null
                    }
                }

                if (isSwiping && swipedCard != null) {
                    swipeOffsetX = (event.x - downX).coerceAtMost(0f)
                    invalidate()
                } else if (isDragging && maxScroll > 0f) {
                    val newScroll = (scrollYOffset - dy).coerceIn(0f, maxScroll)
                    if (newScroll != scrollYOffset) {
                        scrollYOffset = newScroll
                        invalidate()
                    }
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)

                if (longPressTriggered) {
                    longPressTriggered = false
                    pressedCard = null
                    swipedCard = null
                    isSwiping = false
                    swipeOffsetX = 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }

                if (isSwiping && swipedCard != null) {
                    val swipedLeftDist = downX - event.x
                    val target = swipedCard!!
                    if (swipedLeftDist > 40f * density) {
                        if (target.isPinned) {
                            vibrateFeedback()
                            Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
                        } else {
                            vibrateFeedback()
                            clipboard.remove(target.text, context)
                            textTruncateCache.clear()
                            Toast.makeText(context, "تم حذف النص", Toast.LENGTH_SHORT).show()
                        }
                    }
                    swipedCard = null
                    isSwiping = false
                    swipeOffsetX = 0f
                    pressedCard = null
                    velocityTracker?.recycle()
                    velocityTracker = null
                    invalidate()
                    return true
                }

                if (isDragging) {
                    // Compute fling velocity
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val initialVelocityY = velocityTracker?.yVelocity ?: 0f
                    if (abs(initialVelocityY) > minFlingVelocity) {
                        scroller.fling(
                            0, scrollYOffset.toInt(),
                            0, -initialVelocityY.toInt(),
                            0, 0,
                            0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                } else {
                    val x = event.x
                    val y = event.y

                    // Check Header buttons
                    if (y <= headerHeight) {
                        if (abcBounds.contains(x, y)) {
                            resetScroll()
                            onReturnToKeyboard?.invoke()
                            velocityTracker?.recycle()
                            velocityTracker = null
                            return true
                        }

                        if (trashPillBounds.contains(x, y)) {
                            val unpinned = clipboard.items()
                            if (unpinned.isNotEmpty()) {
                                vibrateFeedback()
                                clipboard.clear(context)
                                textTruncateCache.clear()
                                Toast.makeText(context, "تم مسح النصوص غير المثبتة (${unpinned.size}) - النصوص المثبتة محفوظة ✓", Toast.LENGTH_SHORT).show()
                                invalidate()
                            } else {
                                vibrateFeedback()
                                Toast.makeText(context, "لا توجد نصوص غير مثبتة لمسحها (النصوص المثبتة محفوظة ولا تُمسح) 📌", Toast.LENGTH_SHORT).show()
                            }
                            velocityTracker?.recycle()
                            velocityTracker = null
                            return true
                        }

                        if (hideBounds.contains(x, y)) {
                            resetScroll()
                            onClose?.invoke()
                            velocityTracker?.recycle()
                            velocityTracker = null
                            return true
                        }
                    } else {
                        // Check card tap
                        val card = findCardAt(x, y)
                        if (card != null) {
                            vibrateFeedback()
                            if (isDeleteMode) {
                                if (card.isPinned) {
                                    Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
                                } else {
                                    clipboard.remove(card.text, context)
                                    textTruncateCache.clear()
                                    Toast.makeText(context, "تم حذف النص", Toast.LENGTH_SHORT).show()
                                    invalidate()
                                }
                            } else {
                                resetScroll()
                                onItemSelected?.invoke(card.text)
                            }
                            velocityTracker?.recycle()
                            velocityTracker = null
                            return true
                        }
                    }
                }

                isDragging = false
                isSwiping = false
                swipedCard = null
                swipeOffsetX = 0f
                pressedCard = null
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                isDragging = false
                isSwiping = false
                swipedCard = null
                swipeOffsetX = 0f
                pressedCard = null
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }
}
