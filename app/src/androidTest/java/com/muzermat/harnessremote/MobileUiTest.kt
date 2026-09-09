package com.muzermat.harnessremote

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real phone interactions against an isolated Host; no production data. */
class MobileUiTest {
    private fun web(v:View):WebView? {if(v is WebView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)web(v.getChildAt(i))?.let{return it};return null}
    private fun js(s:ActivityScenario<MainActivity>,code:String):String {val latch=CountDownLatch(1);var result="null";s.onActivity{a->val w=web(a.window.decorView);if(w==null)latch.countDown()else w.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(10,TimeUnit.SECONDS));return result}
    private fun until(s:ActivityScenario<MainActivity>,code:String,timeout:Long=20000){val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(js(s,code)=="true")return;Thread.sleep(150)};fail("Timed out: $code; body="+js(s,"document.body?.innerText?.slice(0,1800)"))}
    private fun tap(s:ActivityScenario<MainActivity>,selector:String){
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        Thread.sleep(350) // Let WebView's compositor and IME resize settle before measuring.
        val point=JSONObject(js(s,"(()=>{const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();return {x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio}})()"))
        val offset=IntArray(2);s.onActivity{web(it.window.decorView)!!.getLocationOnScreen(offset)}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(offset[0]+point.getInt("x"),offset[1]+point.getInt("y"))
    }
    @Test fun mobileNavigationDraftKeyboardAndDiagnostics(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        fun dismissKeyboard(s:ActivityScenario<MainActivity>){
            device.waitForIdle();Thread.sleep(800)
            if(js(s,"document.body.classList.contains('mr-phone-keyboard')")=="true"){
                device.pressBack();until(s,"!document.body.classList.contains('mr-phone-keyboard')")
            }
        }
        val pairing=InstrumentationRegistry.getArguments().getString("pairing") ?: error("Isolated fixture pairing required")
        fun screenshot(name:String){device.waitForIdle();Thread.sleep(350);device.takeScreenshot(File(context.getExternalFilesDir(null),"mobile-$name.png"))}
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("testPairing",pairing)).use{s->
            until(s,"Boolean(document.querySelector('#pair')?.onsubmit && document.querySelector('#code').value.length===43)")
            js(s,"document.querySelector('#name').value='Mobile UI QA';document.querySelector('#pair').requestSubmit();true")
            until(s,"Boolean(window.__DSH_TEST_PROBE__ && document.querySelector('#mr-phone-nav-sessions'))",60000)
            until(s,"Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='Continue')")
            js(s,"Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Continue').click();true")
            until(s,"!document.body.innerText.includes('Internal Testing Notice')")
            dismissKeyboard(s)
            js(s,"window.__uiSeed={};window.__uiRpc=async(method,payload)=>{const r=await fetch('/api/'+method,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method,payload})});const v=await r.json();if(!v.result?.ok)throw Error(JSON.stringify(v));return v.result.value};(async()=>{const {workspace}=await __uiRpc('workspace.create',{path:'/tmp'});const session=await __uiRpc('session.create',{workspaceId:workspace.workspaceId,agentPreset:'remote-test'});await __uiRpc('session.rename',{sessionId:session.sessionId,title:'手机界面优化与跨网络连接诊断'});await __uiRpc('session.prompt',{sessionId:session.sessionId,mode:'queue',content:[{type:'text',text:'请确认当前任务进度，并说明下一步安排。'}]});window.__uiSeed=session})().catch(e=>window.__uiSeed.error=e.message);true")
            until(s,"Boolean(window.__uiSeed?.sessionId)")
            val id=org.json.JSONTokener(js(s,"window.__uiSeed.sessionId")).nextValue() as String
            until(s,"Boolean(__DSH_TEST_PROBE__.sessions.list.getSnapshot().byId['$id'] && !__DSH_TEST_PROBE__.sessions.list.getSnapshot().byId['$id'].running)")
            tap(s,"#mr-phone-nav-sessions")
            until(s,"Boolean(document.querySelector('.mr-phone-session[data-session-id=\"$id\"]'))")
            assertEquals("true",js(s,"document.documentElement.scrollWidth<=innerWidth+1"))
            screenshot("sessions")
            tap(s,".mr-phone-session[data-session-id=\"$id\"]")
            until(s,"window.__DSH_TEST_PROBE__.sessions.list.getSnapshot().current==='$id' && !document.querySelector('.mr-phone-screen')")
            until(s,"Boolean(document.querySelector('.uV2eYG_input'))")
            dismissKeyboard(s)
            screenshot("chat")
            js(s,"window.__mobileDraft=__DSH_TEST_PROBE__.conversation.input.for(__DSH_TEST_PROBE__.sessions.scope('$id'));__mobileDraft.setDraft('这是一条尚未发送的手机草稿');true")
            tap(s,".uV2eYG_input")
            until(s,"document.body.classList.contains('mr-phone-keyboard')")
            assertEquals("true",js(s,"getComputedStyle(document.querySelector('.mr-phone-nav')).display==='none' && document.querySelector('.uV2eYG_input').getBoundingClientRect().bottom<=innerHeight"))
            screenshot("keyboard")
            println("Keyboard viewport "+js(s,"JSON.stringify({y:scrollY,h:innerHeight,visual:visualViewport?.height,offset:visualViewport?.offsetTop,header:document.querySelector('.mr-phone-header').getBoundingClientRect().top,editor:document.querySelector('.uV2eYG_input').getBoundingClientRect().bottom})"))
            s.onActivity{a->val w=web(a.window.decorView)!!;val p=IntArray(2);w.getLocationOnScreen(p);println("Native WebView keyboard x=${p[0]} y=${p[1]} h=${w.height}")}
            device.pressBack()
            until(s,"!document.body.classList.contains('mr-phone-keyboard')")
            tap(s,"#mr-phone-nav-sessions");until(s,"Boolean(document.querySelector('.mr-phone-screen'))")
            device.pressBack();until(s,"!document.querySelector('.mr-phone-screen')")
            assertEquals("true",js(s,"__mobileDraft.state.getSnapshot().draft==='这是一条尚未发送的手机草稿'"))
            tap(s,"button[aria-label='新建手机会话']")
            until(s,"Boolean(document.querySelector('.mr-phone-workspace'))")
            screenshot("workspaces")
            tap(s,".mr-phone-workspace")
            until(s,"!document.querySelector('.mr-phone-screen') && __DSH_TEST_PROBE__.sessions.list.getSnapshot().current!=='$id'")
            assertEquals("true",js(s,"!document.querySelector('.mr-phone-error')"))
            dismissKeyboard(s)
            tap(s,"#mr-phone-nav-sessions")
            until(s,"Boolean(document.querySelector('.mr-phone-session[data-session-id=\"$id\"]'))")
            tap(s,".mr-phone-session[data-session-id=\"$id\"]")
            until(s,"__DSH_TEST_PROBE__.sessions.list.getSnapshot().current==='$id'")
            assertEquals("true",js(s,"__mobileDraft.state.getSnapshot().draft==='这是一条尚未发送的手机草稿'"))
            dismissKeyboard(s)
            tap(s,"#mr-phone-nav-settings");until(s,"Boolean(document.querySelector('.mr-phone-settings-card'))")
            screenshot("settings")
            tap(s,".mr-phone-settings-card .mr-phone-secondary")
            until(s,"Boolean(document.querySelector('.VOzbGW_panel'))")
            assertEquals("true",js(s,"document.querySelector('.VOzbGW_panel').getBoundingClientRect().width<=innerWidth+1"))
            screenshot("host-settings")
            device.pressBack();until(s,"!document.querySelector('.VOzbGW_panel')")
            device.wait(Until.findObject(By.desc("电脑连接菜单")),5000)!!.click()
            device.wait(Until.findObject(By.text("连接诊断")),5000)!!.click()
            assertTrue("Pinned identity diagnosis passed",device.wait(Until.hasObject(By.textContains("电脑公钥与证书：验证通过")),20000))
            screenshot("diagnostics")
            device.findObject(By.text("关闭")).click()
            device.waitForIdle();Thread.sleep(350)
            device.wait(Until.findObject(By.desc("电脑连接菜单")),5000)!!.click()
            device.wait(Until.findObject(By.text("返回电脑列表")),5000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("把任务带在身边")),5000))
            screenshot("home")
        }
    }
}
