package com.openswift.keyboard.data

import android.content.ClipboardManager as SystemClipboard
import android.content.ClipDescription
import android.content.Context
import org.json.JSONArray

/** Tracks the most recent clipboard items for the keyboard's clipboard panel. */
class ClipboardHistory(ctx: Context) {

    private val prefs = SecurePreferences.open(ctx, TypedDataStores.CLIPBOARD_HISTORY)

    @Volatile
    private var cachedItems: List<String>? = null

    @Volatile
    private var cachedPinnedItems: List<String>? = null

    @Volatile
    private var cachedPinnedSet: Set<String>? = null

    // Tracks when each text was deleted so old clips sitting in the system clipboard don't resurrect,
    // but any fresh copy of the same text is immediately allowed and captured!
    private val deletedTimestamps = mutableMapOf<String, Long>()

    fun invalidateCache() {
        synchronized(this) {
            cachedItems = null
            cachedPinnedItems = null
            cachedPinnedSet = null
        }
    }

    fun items(): List<String> {
        cachedItems?.let { return it }
        synchronized(this) {
            cachedItems?.let { return it }
            val raw = prefs.getString("items", null)
            val list = if (raw == null) {
                val initialRecent = listOf("pkg update", "68245345")
                save(initialRecent)
                initialRecent
            } else {
                runCatching {
                    val arr = JSONArray(raw)
                    List(arr.length()) { arr.getString(it) }
                }.getOrDefault(emptyList())
            }
            cachedItems = list
            return list
        }
    }

    fun add(text: String): Boolean {
        deletedTimestamps.remove(text)
        val current = items()
        val updated = withCapturedItem(current, text)
        if (updated == current) return false
        save(updated)
        return true
    }

    fun onSystemClipChanged() {
        // When the user performs a new copy in the system, clear transient deletion blocks
        deletedTimestamps.clear()
    }

    fun markAsDeleted(text: String) {
        deletedTimestamps[text] = System.currentTimeMillis()
    }

    fun remove(text: String, ctx: Context? = null) {
        markAsDeleted(text)
        save(items().filter { it != text })
        clearSystemClipIfMatching(ctx, text)
    }

    fun clear(ctx: Context? = null) {
        items().forEach { markAsDeleted(it) }
        save(emptyList())
        if (ctx != null) {
            clearSystemClip(ctx)
        }
    }

    private fun clearSystemClipIfMatching(ctx: Context?, text: String) {
        if (ctx == null || isPinned(text)) return
        try {
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboard ?: return
            val clip = cb.primaryClip ?: return
            if (clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (clipText == text) {
                    clearSystemClip(ctx)
                }
            }
        } catch (_: Exception) {}
    }

    private fun clearSystemClip(ctx: Context) {
        try {
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboard ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                cb.clearPrimaryClip()
            } else {
                cb.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {}
    }

    fun pinnedItems(): List<String> {
        cachedPinnedItems?.let { return it }
        synchronized(this) {
            cachedPinnedItems?.let { return it }
            val raw = prefs.getString("pinned_items", null)
            val list = if (raw == null) {
                val defaultPinned = listOf(
                    "keysigner", "الف مبارك للجميع",
                    "payload.apk", "عبدج محمد ناجبر",
                    "مشاهدة ممتعة للجميع", "عبدالعزيز محمد عبدالله"
                )
                savePinned(defaultPinned)
                defaultPinned
            } else {
                runCatching {
                    val arr = JSONArray(raw)
                    List(arr.length()) { arr.getString(it) }
                }.getOrDefault(emptyList())
            }
            cachedPinnedItems = list
            cachedPinnedSet = list.toHashSet()
            return list
        }
    }

    fun pinnedSet(): Set<String> {
        cachedPinnedSet?.let { return it }
        pinnedItems()
        return cachedPinnedSet ?: emptySet()
    }

    fun isPinned(text: String): Boolean = pinnedSet().contains(text)

    fun removePinned(text: String) {
        savePinned(pinnedItems().filter { it != text })
    }

    fun pin(text: String) {
        val current = pinnedItems()
        if (text !in current) {
            savePinned(listOf(text) + current)
        }
    }

    fun clearPinned() = savePinned(emptyList())

    fun clearAll() {
        clear()
        clearPinned()
    }

    internal fun savePinned(list: List<String>) {
        cachedPinnedItems = list
        cachedPinnedSet = list.toHashSet()
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pinned_items", arr.toString()).apply()
    }

    internal fun save(list: List<String>) {
        cachedItems = list
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun captureSystem(ctx: Context, enabled: Boolean, privateField: Boolean): Boolean {
        if (!enabled || privateField) return false
        val cb = try {
            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? SystemClipboard
        } catch (_: Exception) {
            null
        } ?: return false

        val clip = try {
            cb.primaryClip
        } catch (_: Exception) {
            null
        } ?: return false

        if (clip.itemCount == 0 || isSensitive(clip.description)) return false
        val text = try {
            clip.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (_: Exception) {
            null
        } ?: return false

        if (text.isBlank()) return false

        // Check if this text was deleted earlier
        val deletedTime = deletedTimestamps[text]
        if (deletedTime != null) {
            val clipTime = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    clip.description.timestamp
                } catch (_: Exception) {
                    0L
                }
            } else {
                0L
            }
            val elapsed = System.currentTimeMillis() - deletedTime
            if (clipTime > deletedTime || elapsed > 2500L) {
                // User explicitly re-copied this text after deleting it; accept it!
                deletedTimestamps.remove(text)
            } else {
                // Stale clip sitting in system buffer from before deletion; skip it
                return false
            }
        }

        if (!shouldCapture(enabled, privateField, sensitiveClip = false, text)) return false
        return add(text)
    }

    private fun isSensitive(description: ClipDescription): Boolean {
        val extras = description.extras ?: return false
        return extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ||
            extras.getBoolean(SENSITIVE_CLIPBOARD_EXTRA, false)
    }

    companion object {
        const val MAX_ITEMS = 3000
        private const val SENSITIVE_CLIPBOARD_EXTRA = "android.content.extra.IS_SENSITIVE"

        internal fun shouldCapture(
            enabled: Boolean,
            privateField: Boolean,
            sensitiveClip: Boolean,
            text: String?
        ): Boolean = enabled && !privateField && !sensitiveClip && !text.isNullOrBlank()

        internal fun withCapturedItem(current: List<String>, text: String): List<String> {
            if (text.isBlank() || current.firstOrNull() == text) return current
            val filtered = current.filter { it != text }
            return (listOf(text) + filtered).take(MAX_ITEMS)
        }
    }
}
