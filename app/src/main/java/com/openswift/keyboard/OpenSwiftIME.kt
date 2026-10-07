package com.openswift.keyboard

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openswift.keyboard.data.Settings
import com.openswift.keyboard.data.ClipboardHistory
import com.openswift.keyboard.data.KeyboardLanguages
import com.openswift.keyboard.data.PerAppSettings
import com.openswift.keyboard.data.SnippetExpansionPolicy
import com.openswift.keyboard.data.SnippetManager
import com.openswift.keyboard.engine.WordList
import com.openswift.keyboard.engine.UserDictionary
import com.openswift.keyboard.engine.MultilingualPredictor
import com.openswift.keyboard.engine.LanguageDetector
import com.openswift.keyboard.engine.TextTokenPolicy
import com.openswift.keyboard.engine.WordCommitPolicy
import com.openswift.keyboard.layout.KeyCode as KC
import com.openswift.keyboard.layout.CustomLayoutStore
import com.openswift.keyboard.layout.Layouts
import com.openswift.keyboard.privacy.InputPrivacyPolicy
import com.openswift.keyboard.theme.Themes
import com.openswift.keyboard.theme.ThemeEditor
import com.openswift.keyboard.view.KeyboardView
import com.openswift.keyboard.ui.ClipboardView
import com.openswift.keyboard.ui.EmojiView
import com.openswift.keyboard.ui.MainActivity
import com.openswift.keyboard.ui.TextEditingView
import com.openswift.keyboard.ui.ToolsHubView
import com.openswift.keyboard.voice.VoiceRecognizer

class OpenSwiftIME : InputMethodService() {

    private lateinit var settings: Settings
    private lateinit var clipboard: ClipboardHistory
    private lateinit var wordList: WordList
    private lateinit var userDict: UserDictionary
    private lateinit var predictor: MultilingualPredictor
    private lateinit var languageDetector: LanguageDetector
    private lateinit var snippets: SnippetManager
    private lateinit var perAppSettings: PerAppSettings
    private lateinit var customLayouts: CustomLayoutStore
    private lateinit var themeEditor: ThemeEditor
    private lateinit var keyboardView: KeyboardView
    private lateinit var emojiView: EmojiView
    private lateinit var clipboardView: ClipboardView
    private lateinit var textEditingView: TextEditingView
    private lateinit var toolsHubView: ToolsHubView
    private lateinit var keyboardInputView: View
    private lateinit var emojiInputView: View
    private lateinit var clipboardInputView: View
    private lateinit var textEditingInputView: View
    private lateinit var toolsHubInputView: View
    private lateinit var numberRowView: com.openswift.keyboard.view.NumberRowView
    private lateinit var vibrator: Vibrator

    private var currentLayout = Layouts.Arabic
    private var shiftActive = false
    private var symbolsActive = false
    private var emojiMode = false
    private var clipboardMode = false
    private var numberRowShown = false
    private var previousWord = ""
    private var currentWord = StringBuilder()
    private val snippetBuffer = StringBuilder()
    private var activeLanguageCode = KeyboardLanguages.Arabic.code
    private val languageContext = ArrayDeque<String>()
    private var refreshingLanguage = false
    private var privacyModeActive = false
    private var activeAppConfig = PerAppSettings.AppConfig("")
    private var voiceRecognizer: VoiceRecognizer? = null
    private var isListeningVoice = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        customLayouts = CustomLayoutStore(this)
        themeEditor = ThemeEditor(this)
        clipboard = ClipboardHistory(this)
        predictor = MultilingualPredictor(this)
        languageDetector = LanguageDetector(predictor.supportedLanguages()) { language, word ->
            predictor.frequency(language, word)
        }
        refreshLanguageState()
        snippets = SnippetManager(this)
        perAppSettings = PerAppSettings(this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        Settings.encryptedPreferences(this).registerOnSharedPreferenceChangeListener(prefChangeListener)
        val cb = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cb?.addPrimaryClipChangedListener {
            clipboard.onSystemClipChanged()
            clipboard.captureSystem(this, enabled = true, privateField = privacyModeActive)
            if (::clipboardView.isInitialized) {
                clipboardView.refresh()
            }
        }
    }

    override fun onCreateInputView(): View {
        refreshLanguageState()
        currentLayout = resolveLayout(settings.layout)
        val activeTheme = themeEditor.resolve(settings.theme)
        keyboardView = KeyboardView(
            this,
            settings,
            wordList,
            userDict,
            currentLayout,
            theme = activeTheme,
        )
        applyInputProfile()
        keyboardView.setOnKeyListener { code, label ->
            onKeyPressed(code, label)
        }
        keyboardView.setOnGlideListener { word ->
            commitWord(word)
            snippetBuffer.clear()
        }
        keyboardView.onOpenHub = {
            showToolsHubView()
        }
        keyboardView.onOpenTextEditing = {
            showTextEditingView()
        }
        keyboardView.onOpenClipboard = {
            showClipboardView()
        }
        keyboardView.onOpenVoice = {
            toggleVoiceTyping()
        }
        keyboardView.onHeightChanged = { newHeight ->
            settings.keyHeightDp = newHeight
            applyInputProfile()
            keyboardView.requestLayout()
            keyboardInputView.requestLayout()
            window?.window?.decorView?.requestLayout()
            try {
                updateInputViewShown()
            } catch (_: Exception) {}
        }
        keyboardView.onHideKeyboard = {
            requestHideSelf(0)
        }
        keyboardInputView = withNavigationBarInset(keyboardView, activeTheme.background)
        
        emojiView = EmojiView(this)
        emojiView.onEmojiSelected = { emoji ->
            currentInputConnection?.commitText(emoji, 1)
            clearInputBuffers()
            emojiMode = false
            showKeyboardView()
        }
        emojiView.onClose = {
            emojiMode = false
            showKeyboardView()
        }
        emojiInputView = withNavigationBarInset(emojiView, Themes.Amoled.background)

        clipboardView = ClipboardView(this)
        clipboardView.clipboard = this.clipboard
        clipboardView.onItemSelected = { item ->
            currentInputConnection?.commitText(item, 1)
            clearInputBuffers()
            clipboardMode = false
            showKeyboardView()
        }
        clipboardView.onClose = {
            clipboardMode = false
            showKeyboardView()
        }
        clipboardView.onReturnToKeyboard = {
            clipboardMode = false
            showKeyboardView()
        }
        clipboardInputView = withNavigationBarInset(clipboardView, 0xFF000000.toInt())

        textEditingView = TextEditingView(this)
        textEditingView.onOpenHub = { showToolsHubView() }
        textEditingView.onOpenEmoji = { showEmojiView() }
        textEditingView.onOpenClipboard = { showClipboardView() }
        textEditingView.onClose = { showKeyboardView() }
        textEditingView.onAction = { action -> handleTextEditingAction(action) }
        textEditingInputView = withNavigationBarInset(textEditingView, 0xFF14171E.toInt())

        toolsHubView = ToolsHubView(this)
        toolsHubView.onOpenEmoji = { showEmojiView() }
        toolsHubView.onOpenTextEditing = { showTextEditingView() }
        toolsHubView.onOpenClipboard = { showClipboardView() }
        toolsHubView.onOpenVoice = {
            showKeyboardView()
            toggleVoiceTyping()
        }
        toolsHubView.onClose = { showKeyboardView() }
        toolsHubView.onToolSelected = { tool -> handleToolSelected(tool) }
        toolsHubInputView = withNavigationBarInset(toolsHubView, 0xFF14171E.toInt())

        numberRowView = com.openswift.keyboard.view.NumberRowView(this)
        numberRowView.onKeyListener = { code, label ->
            onKeyPressed(code, label)
        }
        
        return keyboardInputView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        privacyModeActive = InputPrivacyPolicy.shouldUseIncognito(info, settings.incognitoMode)
        activeAppConfig = info?.packageName
            ?.takeIf { it.isNotBlank() }
            ?.let(perAppSettings::getAppConfig)
            ?: PerAppSettings.AppConfig("")
        clearInputBuffers()
        languageContext.clear()
        previousWord = ""
        snippets.reload()
        refreshLanguageState()
        val currentTheme = themeEditor.resolve(settings.theme)
        if (::keyboardView.isInitialized) {
            keyboardView.updateTheme(currentTheme)
            keyboardView.updateLayout(resolveLayout(settings.layout))
        }
        applyInputProfile()
        clipboard.onSystemClipChanged()
        clipboard.captureSystem(
            ctx = this,
            enabled = true,
            privateField = privacyModeActive
        )
        if (::clipboardView.isInitialized) {
            clipboardView.refresh()
        }
        shiftActive = settings.autoCapitalize // Start with shift active if auto-capitalize is on
        symbolsActive = false
        emojiMode = false
        clipboardMode = false
        numberRowShown = false
        if (isListeningVoice) {
            try {
                voiceRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListeningVoice = false
            if (::keyboardView.isInitialized) {
                keyboardView.isVoiceListening = false
            }
        }
        updateSuggestions()
    }

    private fun onKeyPressed(code: Int, label: String) {
        val ic = currentInputConnection ?: return
        when (code) {
            KC.SPACE -> {
                val expanded = expandSnippetIfMatched()
                if (!expanded && currentWord.isNotEmpty()) {
                    commitWord(correctedCurrentWord())
                }
                ic.commitText(" ", 1)
                snippetBuffer.clear()
                updateSuggestions()
                if (settings.autoCapitalize) requestShift(true)
            }
            KC.ENTER -> {
                val expanded = expandSnippetIfMatched()
                if (!expanded && currentWord.isNotEmpty()) commitWord(correctedCurrentWord())
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                clearInputBuffers()
                previousWord = ""
                updateSuggestions()
                if (settings.autoCapitalize) requestShift(true)
            }
            KC.DELETE -> {
                if (currentWord.isNotEmpty()) {
                    currentWord.deleteCharAt(currentWord.length - 1)
                } else {
                    previousWord = ""
                }
                if (snippetBuffer.isNotEmpty()) snippetBuffer.deleteCharAt(snippetBuffer.lastIndex)
                
                val selectedText = ic.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    val deleted = ic.deleteSurroundingText(1, 0)
                    if (!deleted) {
                        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
                    }
                }
                updateSuggestions()
            }
            KC.SHIFT -> {
                shiftActive = !shiftActive
                keyboardView.setShift(shiftActive)
            }
            KC.SYMBOLS -> {
                symbolsActive = true
                currentLayout = Layouts.Numpad
                keyboardView.updateLayout(currentLayout)
            }
            KC.ABC -> {
                symbolsActive = false
                numberRowShown = false
                currentLayout = resolveLayout(settings.layout)
                keyboardView.updateLayout(currentLayout)
            }
            KC.SHIFT_SYMBOLS -> {
                symbolsActive = true
                currentLayout = when (currentLayout.id) {
                    Layouts.Symbols.id -> Layouts.SymbolsShift
                    Layouts.SymbolsShift.id -> Layouts.Symbols
                    else -> Layouts.Symbols
                }
                keyboardView.updateLayout(currentLayout)
            }
            KC.EMOJI -> {
                emojiMode = true
                showEmojiView()
            }
            KC.MIC -> {
                toggleVoiceTyping()
            }
            KC.LANGUAGE -> {
                toggleLanguage()
            }
            KC.CLIPBOARD -> showClipboardView()
            KC.SETTINGS -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                        .putExtra(MainActivity.EXTRA_PER_APP_PACKAGE, activeAppConfig.packageName),
                )
            }
            KC.COMMA -> {
                val text = if (activeLanguageCode == KeyboardLanguages.Arabic.code) "،" else ","
                val expanded = expandSnippetIfMatched()
                if (!expanded && currentWord.isNotEmpty()) commitWord(correctedCurrentWord())
                ic.commitText(text, 1)
                appendSnippetText(text)
                shiftActive = false
                keyboardView.setShift(false)
                updateSuggestions()
            }
            KC.PERIOD -> {
                val expanded = expandSnippetIfMatched()
                if (!expanded && currentWord.isNotEmpty()) commitWord(correctedCurrentWord())
                ic.commitText(".", 1)
                appendSnippetText(".")
                shiftActive = false
                keyboardView.setShift(false)
                updateSuggestions()
            }
            else -> {
                if (label.length > 1) {
                    currentWord.append(label)
                    ic.commitText(label, 1)
                    appendSnippetText(label)
                    updateSuggestions()
                } else if (label.length == 1) {
                    val ch = label[0]
                    val text = if (shiftActive && ch.isLetter()) ch.uppercase() else ch.toString()
                    if (TextTokenPolicy.continuesWord(ch, currentWord)) {
                        currentWord.append(ch)
                        appendSnippetText(text)
                        ic.commitText(text, 1)
                        if (ch.isLetter()) {
                            shiftActive = false
                            keyboardView.setShift(false)
                        }
                    } else {
                        val expanded = expandSnippetIfMatched()
                        if (!expanded && currentWord.isNotEmpty()) commitWord(correctedCurrentWord())
                        ic.commitText(text, 1)
                        appendSnippetText(text)
                    }
                    updateSuggestions()
                }
            }
        }
        if (settings.hapticFeedback) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
    }

    private fun commitWord(word: String) {
        val ic = currentInputConnection ?: return
        val typedWord = currentWord.toString()
        if (learningEnabled()) {
            userDict.learn(previousWord.ifEmpty { null }, word)
            rememberLanguageToken(word)
        }
        val plan = WordCommitPolicy.plan(typedWord, word, shiftActive)
        if (plan.charactersToDelete > 0) {
            ic.deleteSurroundingText(plan.charactersToDelete, 0)
        }
        plan.textToCommit?.let { ic.commitText(it, 1) }
        currentWord.clear()
        previousWord = if (learningEnabled()) word else ""
        shiftActive = false
        keyboardView.setShift(false)
    }

    private fun correctedCurrentWord(): String {
        val typedWord = currentWord.toString()
        if (!predictionsEnabled() || !settings.autoCorrect) return typedWord
        detectLanguageForCurrentContext(typedWord)
        return predictor.autoCorrect(activeLanguageCode, typedWord, previousWord)
    }

    private fun expandSnippetIfMatched(): Boolean {
        val match = SnippetExpansionPolicy.match(snippets, snippetBuffer, privacyModeActive)
            ?: return false
        val inputConnection = currentInputConnection ?: return false
        inputConnection.deleteSurroundingText(match.trigger.length, 0)
        inputConnection.commitText(match.expansion, 1)
        clearInputBuffers()
        previousWord = ""
        shiftActive = false
        keyboardView.setShift(false)
        return true
    }

    private fun appendSnippetText(text: String) {
        if (privacyModeActive) return
        val maximumLength = snippets.maxTriggerLength()
        if (maximumLength == 0) return
        snippetBuffer.append(text)
        val retainedLength = maximumLength + 1
        if (snippetBuffer.length > retainedLength) {
            snippetBuffer.delete(0, snippetBuffer.length - retainedLength)
        }
    }

    private fun clearInputBuffers() {
        currentWord.clear()
        snippetBuffer.clear()
    }

    private fun requestShift(on: Boolean) {
        shiftActive = on
        keyboardView.setShift(on)
    }

    private fun toggleLanguage() {
        symbolsActive = false
        val nextLanguage = if (activeLanguageCode == KeyboardLanguages.Arabic.code) {
            KeyboardLanguages.English.code
        } else {
            KeyboardLanguages.Arabic.code
        }
        settings.language = nextLanguage
        refreshLanguageState()
    }

    private fun updateSuggestions() {
        keyboardView.setSuggestions(emptyList())
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        @Suppress("DEPRECATION")
        val locale = newSubtype?.languageTag?.takeIf { it.isNotBlank() } ?: newSubtype?.locale
        if (!locale.isNullOrBlank()) {
            settings.language = KeyboardLanguages.byLocale(locale).code
            refreshLanguageState()
        }
    }

    private fun refreshLanguageState() {
        refreshingLanguage = true
        val language = KeyboardLanguages.byCode(settings.language)
        activeLanguageCode = language.code
        wordList = predictor.wordList(language.code)
        userDict = predictor.userDictionary(language.code)
        if (!symbolsActive) {
            currentLayout = resolveLayout(settings.layout)
        }
        if (::keyboardView.isInitialized) {
            keyboardView.updateDictionary(wordList, userDict)
            if (!symbolsActive) {
                keyboardView.updateLayout(currentLayout)
            }
            updateSuggestions()
        }
        refreshingLanguage = false
    }

    private fun detectLanguageForCurrentContext(currentToken: String) {
        if (!settings.languageDetection) return
        val tokens = languageContext + listOf(currentToken)
        val detected = languageDetector.detect(tokens, activeLanguageCode) ?: return
        settings.language = detected.languageCode
        refreshLanguageState()
    }

    private fun resolveLayout(id: String) = customLayouts.load(id)?.layout ?: Layouts.byId(id)

    private fun rememberLanguageToken(word: String) {
        val token = word.lowercase().filter { it.isLetter() || it == '¡' || it == '¿' }
        if (token.length < 2) return
        languageContext.addLast(token)
        while (languageContext.size > LANGUAGE_CONTEXT_LIMIT) {
            languageContext.removeFirst()
        }
    }

    private fun showEmojiView() {
        setInputView(emojiInputView)
    }

    private fun showToolsHubView() {
        setInputView(toolsHubInputView)
    }

    private fun showTextEditingView() {
        setInputView(textEditingInputView)
    }

    private fun showClipboardView() {
        clipboard.onSystemClipChanged()
        clipboard.captureSystem(this, enabled = true, privateField = privacyModeActive)
        clipboardMode = true
        clipboardView.refresh()
        setInputView(clipboardInputView)
    }

    private fun showKeyboardView() {
        setInputView(keyboardInputView)
    }

    private fun handleTextEditingAction(action: TextEditingView.Action) {
        val ic = currentInputConnection ?: return
        when (action) {
            TextEditingView.Action.CLIPBOARD -> showClipboardView()
            TextEditingView.Action.UP -> {
                if (textEditingView.isSelectionModeActive) {
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, KeyEvent.META_SHIFT_ON))
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, KeyEvent.META_SHIFT_ON))
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
                }
            }
            TextEditingView.Action.DOWN -> {
                if (textEditingView.isSelectionModeActive) {
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_SHIFT_ON))
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_SHIFT_ON))
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                }
            }
            TextEditingView.Action.LEFT -> {
                if (textEditingView.isSelectionModeActive) {
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_SHIFT_ON))
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_SHIFT_ON))
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                }
            }
            TextEditingView.Action.RIGHT -> {
                if (textEditingView.isSelectionModeActive) {
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_SHIFT_ON))
                    ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_SHIFT_ON))
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                }
            }
            TextEditingView.Action.SELECT_TOGGLE -> {
                textEditingView.isSelectionModeActive = !textEditingView.isSelectionModeActive
            }
            TextEditingView.Action.SELECT_ALL -> {
                ic.performContextMenuAction(android.R.id.selectAll)
            }
            TextEditingView.Action.COPY -> {
                val selected = ic.getSelectedText(0)?.toString()
                if (!selected.isNullOrBlank()) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("OpenSwift", selected))
                    clipboard.add(selected)
                    clipboardView.refresh()
                } else {
                    ic.performContextMenuAction(android.R.id.copy)
                    clipboard.captureSystem(this, enabled = true, privateField = false)
                    clipboardView.refresh()
                }
            }
            TextEditingView.Action.CUT -> {
                val selected = ic.getSelectedText(0)?.toString()
                if (!selected.isNullOrBlank()) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("OpenSwift", selected))
                    clipboard.add(selected)
                    clipboardView.refresh()
                }
                ic.performContextMenuAction(android.R.id.cut)
            }
            TextEditingView.Action.PASTE -> {
                val latest = clipboard.items().firstOrNull()
                if (latest != null) {
                    ic.commitText(latest, 1)
                } else {
                    ic.performContextMenuAction(android.R.id.paste)
                }
            }
            TextEditingView.Action.DELETE -> {
                ic.deleteSurroundingText(1, 0)
            }
            TextEditingView.Action.SPACE -> {
                ic.commitText(" ", 1)
            }
            TextEditingView.Action.HOME -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME))
            }
            TextEditingView.Action.END -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
            }
            TextEditingView.Action.UNDO -> {
                ic.performContextMenuAction(android.R.id.undo)
                ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
                ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
            }
        }
        if (settings.hapticFeedback) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
    }

    private fun handleToolSelected(tool: ToolsHubView.ToolId) {
        when (tool) {
            ToolsHubView.ToolId.SETTINGS -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                )
            }
            ToolsHubView.ToolId.THEME -> {
                val allThemes = Themes.all
                val currentIdx = allThemes.indexOfFirst { it.id == settings.theme }.coerceAtLeast(0)
                val nextTheme = allThemes[(currentIdx + 1) % allThemes.size]
                settings.theme = nextTheme.id
                refreshLanguageState()
                showKeyboardView()
            }
            ToolsHubView.ToolId.VOICE -> {
                showKeyboardView()
                toggleVoiceTyping()
            }
            ToolsHubView.ToolId.NEXT_LANGUAGE -> {
                toggleLanguage()
                showKeyboardView()
            }
            ToolsHubView.ToolId.EMOJI -> {
                showEmojiView()
            }
            ToolsHubView.ToolId.RESIZE -> {
                showKeyboardView()
                keyboardView.isResizeMode = true
                android.widget.Toast.makeText(this, "استخدم + و - لتعديل ارتفاع لوحة المفاتيح ثم اضغط تم", android.widget.Toast.LENGTH_LONG).show()
            }
            ToolsHubView.ToolId.FLOATING -> {
                showKeyboardView()
            }
            ToolsHubView.ToolId.TRANSLATE -> {
                showKeyboardView()
            }
            ToolsHubView.ToolId.GIF -> {
                showEmojiView()
            }
            ToolsHubView.ToolId.STICKER -> {
                showEmojiView()
            }
            ToolsHubView.ToolId.CUSTOMIZE -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                )
            }
        }
    }

    private fun withNavigationBarInset(content: View, backgroundColor: Int): View =
        FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                if (view.paddingBottom != bottomInset) {
                    view.setPadding(0, 0, 0, bottomInset)
                }
                insets
            }
        }

    override fun onFinishInput() {
        super.onFinishInput()
        if (currentWord.isNotEmpty() && learningEnabled()) {
            detectLanguageForCurrentContext(currentWord.toString())
            userDict.learn(previousWord.ifEmpty { null }, currentWord.toString())
            rememberLanguageToken(currentWord.toString())
            userDict.save()
        }
        clearInputBuffers()
        languageContext.clear()
        previousWord = ""
        if (isListeningVoice) {
            try {
                voiceRecognizer?.stopListening()
            } catch (_: Exception) {}
            currentInputConnection?.finishComposingText()
            isListeningVoice = false
            if (::keyboardView.isInitialized) {
                keyboardView.isVoiceListening = false
            }
        }
    }

    private fun predictionsEnabled(): Boolean = false

    private fun learningEnabled(): Boolean = predictionsEnabled()

    private fun toggleVoiceTyping() {
        if (isListeningVoice) {
            stopVoiceTyping()
        } else {
            startVoiceTyping()
        }
    }

    private fun startVoiceTyping() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val intent = Intent(this, com.openswift.keyboard.voice.VoicePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }
        try {
            if (voiceRecognizer == null) {
                voiceRecognizer = VoiceRecognizer(this)
            }
            voiceRecognizer?.apply {
                onPartialResult = { partialText ->
                    val ic = currentInputConnection
                    if (partialText.isNotBlank()) {
                        ic?.setComposingText(partialText, 1)
                    } else {
                        ic?.setComposingText("", 1)
                    }
                }
                onResult = { finalText ->
                    val ic = currentInputConnection
                    if (finalText.isNotBlank()) {
                        ic?.commitText("$finalText ", 1)
                        updateSuggestions()
                    } else {
                        ic?.setComposingText("", 1)
                        ic?.finishComposingText()
                    }
                }
                onStateChanged = { listening ->
                    isListeningVoice = listening
                    if (!listening) {
                        currentInputConnection?.finishComposingText()
                    }
                    if (::keyboardView.isInitialized) {
                        keyboardView.isVoiceListening = listening
                        keyboardView.invalidate()
                    }
                }
                onError = { _ ->
                    currentInputConnection?.finishComposingText()
                    isListeningVoice = false
                    if (::keyboardView.isInitialized) {
                        keyboardView.isVoiceListening = false
                        keyboardView.invalidate()
                    }
                }
            }
            val lang = if (activeLanguageCode == KeyboardLanguages.Arabic.code) "ar-SA" else "en-US"
            isListeningVoice = true
            if (::keyboardView.isInitialized) {
                keyboardView.isVoiceListening = true
                keyboardView.invalidate()
            }
            voiceRecognizer?.startListening(lang, continuous = true)
        } catch (_: Exception) {
            currentInputConnection?.finishComposingText()
            isListeningVoice = false
            if (::keyboardView.isInitialized) {
                keyboardView.isVoiceListening = false
                keyboardView.invalidate()
            }
        }
    }

    private fun stopVoiceTyping() {
        try {
            voiceRecognizer?.stopListening()
        } catch (_: Exception) {}
        currentInputConnection?.finishComposingText()
        isListeningVoice = false
        if (::keyboardView.isInitialized) {
            keyboardView.isVoiceListening = false
            keyboardView.invalidate()
        }
    }

    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme" || key == "layout" || key == "key_height_dp") {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (::keyboardView.isInitialized) {
                    val resolvedTheme = themeEditor.resolve(settings.theme)
                    keyboardView.updateTheme(resolvedTheme)
                    keyboardView.updateLayout(resolveLayout(settings.layout))
                    applyInputProfile()
                    keyboardView.invalidate()
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            Settings.encryptedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Exception) {}
        voiceRecognizer?.destroy()
        voiceRecognizer = null
        super.onDestroy()
    }

    private fun applyInputProfile() {
        if (!::keyboardView.isInitialized) return
        val effective = PerAppSettings.resolve(
            activeAppConfig,
            globalGlideEnabled = settings.glideEnabled,
            globalKeyHeightDp = settings.keyHeightDp,
        )
        keyboardView.setInputProfile(
            predictionsEnabled = !privacyModeActive && effective.predictionsEnabled,
            glideEnabled = !privacyModeActive && effective.glideEnabled,
            keyHeightDp = effective.keyHeightDp,
        )
        if (::clipboardView.isInitialized) {
            clipboardView.keyHeightDp = effective.keyHeightDp
        }
        if (::textEditingView.isInitialized) {
            textEditingView.keyHeightDp = effective.keyHeightDp
        }
        if (::toolsHubView.isInitialized) {
            toolsHubView.keyHeightDp = effective.keyHeightDp
        }
        if (::keyboardInputView.isInitialized) {
            keyboardInputView.requestLayout()
        }
        window?.window?.decorView?.requestLayout()
        try {
            updateInputViewShown()
        } catch (_: Exception) {}
    }

    companion object {
        private const val LANGUAGE_CONTEXT_LIMIT = 8
    }
}
