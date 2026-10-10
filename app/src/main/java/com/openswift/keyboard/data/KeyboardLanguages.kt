package com.openswift.keyboard.data

data class KeyboardLanguage(
    val code: String,
    val name: String,
    val locale: String,
    val layoutId: String,
    val wordListRes: Int = 0
)

object KeyboardLanguages {
    val English = KeyboardLanguage("en", "English", "en_US", "qwerty", 0)
    val Arabic = KeyboardLanguage("ar", "العربية (Arabic)", "ar", "arabic", 0)

    val all = listOf(
        English,
        Arabic
    )

    fun byCode(code: String?): KeyboardLanguage {
        val normalized = code.orEmpty().lowercase().substringBefore('_').substringBefore('-')
        return all.firstOrNull { it.code == normalized } ?: English
    }

    fun byLocale(locale: String?): KeyboardLanguage = byCode(locale)
}
