package com.batraamul.cycling.ui

import android.graphics.Bitmap
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.batraamul.cycling.CyclingApp
import com.batraamul.cycling.data.DailyActivity
import com.batraamul.cycling.ui.theme.AppThemes
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the real Today and Settings screens in every theme to PNG files, so the look can be
 * reviewed without a phone. Opt-in (slow): ./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
 * Output: app/build/screenshots/. Robolectric's native graphics draws with the same Skia engine as Android.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")   // roughly a OnePlus 11R in portrait
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderEveryTheme() = runBlocking {
        assumeTrue(System.getProperty("screenshots") == "true")
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<CyclingApp>()
        val db = app.container.db
        db.settings().save(db.settings().getOrDefault().copy(onboardingDone = true, themeId = 0))
        db.tracking().saveDay(DailyActivity(LocalDate.now().toString(), 6240, 4400.0, 1))
        val vm = AppViewModel(app)
        compose.setContent { CyclingAppUi(vm) }
        for (t in AppThemes) {
            db.settings().save(db.settings().getOrDefault().copy(themeId = t.id))
            awaitTrue { vm.settings.value?.themeId == t.id }
            compose.waitForIdle()
            save(dir, "today-${t.id}-${t.name.replace(' ', '_')}")
        }
    }

    /** Lets the main thread and Room's background threads run until [cond] holds (Robolectric's main looper is paused). */
    private fun awaitTrue(cond: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        error("condition not reached")
    }

    private fun save(dir: File, name: String) {
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
