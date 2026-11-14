package app.duck.notes.qualification

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.DuckApp
import app.duck.notes.presentation.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-service interaction qualification; it does not certify spoken audio or physical devices. */
@SdkSuppress(minSdkVersion = 35)
@RunWith(AndroidJUnit4::class)
class TalkBackQualificationTest {
    @Test fun actualTalkBackAndHardwareKeyboardCanFocusProductionControls() {
        assumeTrue("Explicit qualification only", InstrumentationRegistry.getArguments().getString("talkback_qualification") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Use the real-Application runner", ApplicationProvider.getApplicationContext<Application>() is DuckApp)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = context.packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE).setPackage("com.google.android.marvin.talkback"), PackageManager.GET_META_DATA)
        val service = services.single { it.serviceInfo.name.endsWith(".TalkBackService") }.serviceInfo
        val component = ComponentName(service.packageName, service.name).flattenToString()
        val resolver = context.contentResolver
        val originalServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val originalEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val accessibilityFocus = AtomicInteger()
        val keyboardFocus = AtomicInteger()
        automation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == "app.duck.notes") {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) accessibilityFocus.incrementAndGet()
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) keyboardFocus.incrementAndGet()
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        fun bound() = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .any { it.resolveInfo.serviceInfo.name == service.name }
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            val enabled = (originalServices.orEmpty().split(':').filter { it.isNotBlank() } + component).distinct().joinToString(":")
            assertTrue(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled))
            assertTrue(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1"))
            await("Installed TalkBack never bound with touch exploration") { bound() && manager.isTouchExplorationEnabled }
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var files: AccessibilityNodeInfo? = null
            await("Production Files control is unavailable to accessibility") {
                fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.packageName?.toString() == "app.duck.notes" && node.text?.toString() == "Files") return node
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { find(it) }?.let { return it }
                    }
                    return null
                }
                files = automation.rootInActiveWindow?.let(::find)
                files != null
            }
            val target = requireNotNull(files)
            target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            assertTrue("Files does not accept accessibility focus", target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            await("No real app accessibility-focus event was delivered") { accessibilityFocus.get() > 0 }
            val before = accessibilityFocus.get() + keyboardFocus.get()
            repeat(4) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            }
            await("Keyboard traversal did not move production focus") { accessibilityFocus.get() + keyboardFocus.get() > before }
            assertTrue("TalkBack was suppressed during the check", bound() && manager.isTouchExplorationEnabled)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("talkback_component", component)
                putInt("accessibility_focus_events", accessibilityFocus.get())
                putInt("keyboard_focus_events", keyboardFocus.get())
            })
        } finally {
            automation.setOnAccessibilityEventListener(null)
            try {
                assertTrue(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices))
            } finally {
                try { assertTrue(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled)) }
                finally { automation.dropShellPermissionIdentity() }
            }
        }
    }
}
