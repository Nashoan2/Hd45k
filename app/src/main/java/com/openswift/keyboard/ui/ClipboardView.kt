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
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.openswift.keyboard.R
import com.openswift.keyboard.data.ClipboardHistory
import kotlin.math.abs
import kotlin.math.max

/**
 * Clipboard View matching حافظة.png with rich, intuitive deletion and item management:
 * 1. Dedicated prominent [ 🗑️ حذف ] button in the top header to enter Delete Mode.
 * 2. In Delete Mode: every item has a visible red ✕ delete badge, single tap instantly deletes item.
 * 3. Clear All button [ مسح الكل ] in Delete Mode.
 * 4. In Normal Mode: Long-press on any item opens a quick Action Dialog with [ 🗑️ حذف من الحافظة ].
 * 5. Horizontal swipe-to-delete also supported.
 * 6. Synchronous, permanent deletion via ClipboardHistory.
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

    // Modal Action Dialog for long-press
    private var activeDialogItem: String? = null
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
    private val deletePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A1A1E.toInt()
        style = Paint.Style.FILL
    }
    private val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 14f * density
        color = 0xFF969DA9.toInt()
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4F58.toInt() // Solid rounded dark grey matching screenshot
        style = Paint.Style.FILL
    }
    private val cardDeleteModeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3F2024.toInt() // Subtle reddish dark grey in delete mode
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
        color = 0xFFEF4444.toInt() // Red badge
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
    private val clipTouchTargets = mutableMapOf<String, RectF>()

    // Swipe to delete state
    private var swipedItem: String? = null
    private var isSwiping = false
    private var swipeOffsetX = 0f

    // Dimensions
    private val headerHeight = 48f * density
    private val horizontalMargin = 12f * density
    private val cardHeight = 44f * density
    private val cardRadius = 8f * density
    private val gridGap = 8f * density

    // Scroll state
    private var scrollYOffset = 0f
    private var maxScroll = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    // Long press detection for deleting
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pressedItem: String? = null
    private var longPressTriggered = false
    private var ignoreNextUpForDialog = false
    private val longPressRunnable = Runnable {
        pressedItem?.let { item ->
            longPressTriggered = true
            ignoreNextUpForDialog = true
            vibrateFeedback()
            activeDialogItem = item
            invalidate()
        }
    }

    fun refresh() {
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val keyHeightPx = keyHeightDp * density
        val targetHeight = (4 * keyHeightPx) + (2f * density * 3) + (keyHeightPx * 0.82f) + (8f * density)
        setMeasuredDimension(w, targetHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Fill pitch-black background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Draw Header
        drawHeader(canvas, w)

        // 3. Draw Scrollable Content (الأحدث + العناصر المثبتة)
        canvas.save()
        canvas.clipRect(0f, headerHeight, w, h)

        clipTouchTargets.clear()
        var curY = headerHeight + (10f * density) - scrollYOffset

        val recentItems = clipboard.items()
        val pinnedItems = clipboard.pinnedItems()

        if (recentItems.isEmpty() && pinnedItems.isEmpty()) {
            val emptyCenterY = (h - headerHeight) / 2f + headerHeight
            canvas.drawText("الحافظة فارغة", w / 2f, emptyCenterY - (10f * density), headerTitlePaint)
            canvas.drawText("قم بنسخ أي نص وسيظهر هنا تلقائياً", w / 2f, emptyCenterY + (16f * density), emptySubPaint)
            canvas.restore()
            if (activeDialogItem != null) drawActionDialog(canvas, w, h)
            return
        }

        // Section 1: الأحدث (Recent)
        if (recentItems.isNotEmpty()) {
            canvas.drawText("الأحدث", w - horizontalMargin, curY + (14f * density), sectionHeaderPaint)
            curY += 24f * density

            for (i in recentItems.indices step 2) {
                val rightText = recentItems[i]
                val leftText = recentItems.getOrNull(i + 1)
                curY = drawTwoColumnRow(canvas, w, curY, rightText, leftText)
                curY += gridGap
            }
            curY += 10f * density
        }

        // Section 2: العناصر المثبَّتة (Pinned items)
        if (pinnedItems.isNotEmpty()) {
            canvas.drawText("العناصر المثبَّتة", w - horizontalMargin, curY + (14f * density), sectionHeaderPaint)
            curY += 24f * density

            for (i in pinnedItems.indices step 2) {
                val rightText = pinnedItems[i]
                val leftText = pinnedItems.getOrNull(i + 1)
                curY = drawTwoColumnRow(canvas, w, curY, rightText, leftText)
                curY += gridGap
            }
        }

        val totalContentHeight = (curY + scrollYOffset) - headerHeight
        maxScroll = max(0f, totalContentHeight - (h - headerHeight))

        canvas.restore()

        // 4. If Action Dialog is open, draw overlay modal
        if (activeDialogItem != null) {
            drawActionDialog(canvas, w, h)
        }
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

        // Right side: [ ∨ ] hide button on the far right
        val iconSize = (27f * density).toInt()
        val hideRight = (w - horizontalMargin).toInt()
        val hideLeft = hideRight - iconSize
        val iconTop = ((headerHeight - iconSize) / 2f).toInt()
        val iconBottom = iconTop + iconSize
        hideBounds.set(hideLeft.toFloat() - (6f * density), 0f, w, headerHeight)

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

    private fun drawTwoColumnRow(
        canvas: Canvas,
        w: Float,
        y: Float,
        rightText: String?,
        leftText: String?
    ): Float {
        val totalAvailableWidth = w - (horizontalMargin * 2f) - gridGap
        val colWidth = totalAvailableWidth / 2f

        val isVisible = (y + cardHeight >= headerHeight && y <= height.toFloat())

        // Right column card (in RTL: right side comes first)
        if (!rightText.isNullOrBlank()) {
            val rightLeft = w - horizontalMargin - colWidth
            val rect = RectF(rightLeft, y, rightLeft + colWidth, y + cardHeight)
            clipTouchTargets[rightText] = rect
            if (isVisible) drawCard(canvas, rect, rightText)
        }

        // Left column card
        if (!leftText.isNullOrBlank()) {
            val rect = RectF(horizontalMargin, y, horizontalMargin + colWidth, y + cardHeight)
            clipTouchTargets[leftText] = rect
            if (isVisible) drawCard(canvas, rect, leftText)
        }

        return y + cardHeight
    }

    private fun drawCard(canvas: Canvas, rect: RectF, text: String) {
        val isBeingSwiped = isSwiping && swipedItem == text && swipeOffsetX < 0f

        if (isBeingSwiped) {
            // Draw red background behind swiped card to indicate deletion
            val swipeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFDC2626.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, cardRadius, cardRadius, swipeBgPaint)

            // Draw white trash can icon on the right side of the card
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

        val isPinned = text in clipboard.pinnedItems()
        val textY = rect.centerY() - ((cardTextPaint.ascent() + cardTextPaint.descent()) / 2f)
        val maxTextWidth = rect.width() - (if (isDeleteMode) 34f * density else 16f * density)
        val clippedText = truncateText(text, maxTextWidth)
        canvas.drawText(clippedText, rect.centerX(), textY, cardTextPaint)

        // Draw pin indicator for pinned items
        if (isPinned) {
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11f * density
                textAlign = Paint.Align.LEFT
            }
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
        val item = activeDialogItem ?: return

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
        val titleText = truncateText(item, dialogWidth - (32f * density))
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
        val isPinned = item in clipboard.pinnedItems()
        val pinLabel = if (isPinned) "📌 إلغاء التثبيت (للسماح بالحذف)" else "📌 تثبيت في أعلى الحافظة (حماية من الحذف)"
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

    private fun truncateText(text: String, maxWidth: Float): String {
        if (cardTextPaint.measureText(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && cardTextPaint.measureText("$truncated…") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return if (truncated.isEmpty()) text.take(3) else "$truncated…"
    }

    fun deleteItem(item: String): Boolean {
        if (item in clipboard.pinnedItems()) {
            Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
            return false
        }
        clipboard.remove(item, context)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If Action Dialog is visible, handle dialog clicks
        if (activeDialogItem != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (ignoreNextUpForDialog) {
                    ignoreNextUpForDialog = false
                    return true
                }
                val item = activeDialogItem!!
                val x = event.x
                val y = event.y

                if (dialogPasteBounds.contains(x, y)) {
                    vibrateFeedback()
                    activeDialogItem = null
                    onItemSelected?.invoke(item)
                    invalidate()
                    return true
                } else if (dialogPinBounds.contains(x, y)) {
                    vibrateFeedback()
                    if (item in clipboard.pinnedItems()) {
                        clipboard.removePinned(item)
                        clipboard.add(item)
                        Toast.makeText(context, "تم إلغاء التثبيت (يمكنك حذفه الآن) ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.pin(item)
                        clipboard.remove(item, context)
                        Toast.makeText(context, "تم تثبيت النص في الحافظة (محمي من الحذف) 📌", Toast.LENGTH_SHORT).show()
                    }
                    activeDialogItem = null
                    invalidate()
                    return true
                } else if (dialogDeleteBounds.contains(x, y)) {
                    vibrateFeedback()
                    if (item in clipboard.pinnedItems()) {
                        Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
                    } else {
                        deleteItem(item)
                        Toast.makeText(context, "تم حذف النص من الحافظة", Toast.LENGTH_SHORT).show()
                        activeDialogItem = null
                        invalidate()
                    }
                    return true
                } else if (dialogCancelBounds.contains(x, y) || !dialogRect.contains(x, y)) {
                    activeDialogItem = null
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
                downX = event.x
                downY = event.y
                isDragging = false
                isSwiping = false
                swipeOffsetX = 0f
                longPressTriggered = false
                pressedItem = null
                swipedItem = null

                // Check if down on a clip item for swipe or long press
                for ((text, rect) in clipTouchTargets) {
                    if (rect.contains(event.x, event.y)) {
                        pressedItem = text
                        swipedItem = text
                        longPressHandler.postDelayed(longPressRunnable, 350L)
                        break
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY

                if (!isDragging && !isSwiping) {
                    // Check if swiping right-to-left on a card (dx negative, horizontal dominance)
                    if (dx < -touchSlop && abs(dx) > abs(dy) * 1.1f && swipedItem != null) {
                        if (swipedItem !in clipboard.pinnedItems()) {
                            isSwiping = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                        } else {
                            // Pinned items are protected from swipe deletion
                            swipedItem = null
                        }
                    } else if (abs(dy) > touchSlop) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        swipedItem = null
                    }
                }

                if (isSwiping && swipedItem != null) {
                    // Swiping right-to-left: negative offset
                    swipeOffsetX = (event.x - downX).coerceAtMost(0f)
                    invalidate()
                } else if (isDragging && maxScroll > 0f) {
                    scrollYOffset = (scrollYOffset - (event.y - downY)).coerceIn(0f, maxScroll)
                    downY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)

                if (longPressTriggered) {
                    longPressTriggered = false
                    pressedItem = null
                    swipedItem = null
                    isSwiping = false
                    swipeOffsetX = 0f
                    return true
                }

                if (isSwiping && swipedItem != null) {
                    val swipedLeftDist = downX - event.x
                    val target = swipedItem!!
                    if (swipedLeftDist > 40f * density) {
                        if (target in clipboard.pinnedItems()) {
                            vibrateFeedback()
                            Toast.makeText(context, "النصوص المثبتة لا يتم مسحها إلا بعد إلغاء التثبيت 📌", Toast.LENGTH_SHORT).show()
                        } else {
                            vibrateFeedback()
                            deleteItem(target)
                            Toast.makeText(context, "تم حذف النص", Toast.LENGTH_SHORT).show()
                        }
                    }
                    swipedItem = null
                    isSwiping = false
                    swipeOffsetX = 0f
                    pressedItem = null
                    invalidate()
                    return true
                }

                if (!isDragging) {
                    val x = event.x
                    val y = event.y

                    // Check ABC button
                    if (abcBounds.contains(x, y)) {
                        onReturnToKeyboard?.invoke()
                        return true
                    }

                    // Check Clear button in header [ 🗑️ مسح ]
                    if (trashPillBounds.contains(x, y)) {
                        val unpinned = clipboard.items()
                        if (unpinned.isNotEmpty()) {
                            vibrateFeedback()
                            clipboard.clear(context)
                            Toast.makeText(context, "تم مسح النصوص غير المثبتة (${unpinned.size}) - النصوص المثبتة محفوظة ✓", Toast.LENGTH_SHORT).show()
                            invalidate()
                        } else {
                            vibrateFeedback()
                            Toast.makeText(context, "لا توجد نصوص غير مثبتة لمسحها (النصوص المثبتة محفوظة ولا تُمسح) 📌", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }

                    // Check Hide button
                    if (hideBounds.contains(x, y)) {
                        onClose?.invoke()
                        return true
                    }

                    // Check clip items tap
                    for ((text, rect) in clipTouchTargets) {
                        if (rect.contains(x, y)) {
                            onItemSelected?.invoke(text)
                            return true
                        }
                    }
                }

                isDragging = false
                isSwiping = false
                swipedItem = null
                swipeOffsetX = 0f
                pressedItem = null
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                isDragging = false
                isSwiping = false
                swipedItem = null
                swipeOffsetX = 0f
                pressedItem = null
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }
}
