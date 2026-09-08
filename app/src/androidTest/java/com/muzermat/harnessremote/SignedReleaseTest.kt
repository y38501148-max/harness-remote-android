package com.muzermat.harnessremote
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.Assert.*

/** Drives the installed non-debug APK entirely through Android UI accessibility. */
class SignedReleaseTest {
    @Test fun pairsUsingNativePasteUi(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        val pairing=InstrumentationRegistry.getArguments().getString("pairing") ?: error("Fixture pairing required")
        val launch=Intent().setClassName("com.muzermat.harnessremote","com.muzermat.harnessremote.MainActivity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("testPairing","ignored-in-release")
        instrumentation.targetContext.startActivity(launch)
        val paste=device.wait(Until.findObject(By.text("粘贴配对链接")),10000)
        assertNotNull("Release ignores debug-only Intent",paste);paste.click();device.waitForIdle()
        val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000)!!
        input.text=pairing
        assertTrue("Exact pairing URL entered",input.text==pairing)
        device.findObject(By.text("连接")).click()
        val request=device.wait(Until.findObject(By.text("请求连接")),20000)
        assertNotNull("Pinned pairing page opened",request);device.waitForIdle();request.click()
        assertTrue("Real Harness client opened",device.wait(Until.hasObject(By.textContains("Into the Unknown")),30000) || device.hasObject(By.text("Internal Testing Notice")) || device.hasObject(By.text("Choose workspace")))
        device.takeScreenshot(java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"signed-paired.png"))
    }
    @Test fun restoresAfterCoverInstall(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(instrumentation)
        val launch=Intent().setClassName("com.muzermat.harnessremote","com.muzermat.harnessremote.MainActivity")
        instrumentation.targetContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Signed app restored encrypted authorization",device.wait(Until.hasObject(By.textContains("Into the Unknown")),30000) || device.hasObject(By.text("Internal Testing Notice")) || device.hasObject(By.text("Choose workspace")))
        device.takeScreenshot(java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"signed-restored.png"))
    }
}
