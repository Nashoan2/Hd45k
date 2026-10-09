package com.openswift.keyboard.view

import android.view.View
import com.openswift.keyboard.data.MutableMapSettingsStore
import com.openswift.keyboard.data.Settings
import com.openswift.keyboard.engine.WordList
import com.openswift.keyboard.engine.emptyUserDictionary
import com.openswift.keyboard.layout.Layouts
import com.openswift.keyboard.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardViewTest {

    @Test
    fun toolbarShortcutsBarRemainsVisibleWhenPredictionsAreDisabledInPasswordFields() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(MutableMapSettingsStore())
        val wordList = WordList.fromEntries(emptyMap())
        val userDict = emptyUserDictionary()
        val view = KeyboardView(
            ctx = context,
            settings = settings,
            wordList = wordList,
            userDict = userDict,
            keyLayout = Layouts.Arabic,
            theme = Themes.Amoled,
        )

        // Initial measure with predictions enabled
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val initialHeight = view.measuredHeight
        assertTrue("Keyboard should have measured height", initialHeight > 0)
        assertTrue("Toolbar shortcuts bar should be visible initially", view.isToolbarVisible())

        // Simulating entering a password field where predictions are disabled for privacy
        view.setInputProfile(
            predictionsEnabled = false,
            glideEnabled = false,
            keyHeightDp = 78,
        )

        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        // The height must NOT collapse or shrink away the toolbar shortcuts strip!
        assertEquals(
            "Shortcuts toolbar must not collapse when entering password fields",
            initialHeight,
            view.measuredHeight,
        )
        assertTrue(
            "Shortcuts toolbar must remain visible in password fields",
            view.isToolbarVisible(),
        )
    }

    @Test
    fun changingKeyHeightChangesMeasuredHeightWhileToolbarHeightRemainsDecoupled() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(MutableMapSettingsStore())
        val wordList = WordList.fromEntries(emptyMap())
        val userDict = emptyUserDictionary()
        val view = KeyboardView(
            ctx = context,
            settings = settings,
            wordList = wordList,
            userDict = userDict,
            keyLayout = Layouts.Arabic,
            theme = Themes.Amoled,
        )

        view.setInputProfile(
            predictionsEnabled = true,
            glideEnabled = false,
            keyHeightDp = 60,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val heightAt60 = view.measuredHeight

        view.setInputProfile(
            predictionsEnabled = true,
            glideEnabled = false,
            keyHeightDp = 80,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val heightAt80 = view.measuredHeight

        assertTrue("Height at 80dp should be greater than height at 60dp", heightAt80 > heightAt60)
        assertEquals(44f, KeyboardView.TOOLBAR_HEIGHT_DP)
    }
}
