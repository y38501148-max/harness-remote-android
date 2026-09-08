package com.muzermat.harnessremote
import android.content.Intent
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClientTest {
    private fun web(v:View):WebView? {if(v is WebView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)web(v.getChildAt(i))?.let{return it};return null}
    private fun js(s:ActivityScenario<MainActivity>,code:String):String {val latch=CountDownLatch(1);var result="null";s.onActivity{a->val w=web(a.window.decorView);if(w==null)latch.countDown()else w.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(10,TimeUnit.SECONDS));return result}
    private fun until(s:ActivityScenario<MainActivity>,code:String,timeout:Long=30000){val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(js(s,code)=="true")return;Thread.sleep(200)};fail("Timed out: $code; body="+js(s,"JSON.stringify({body:document.body?.innerText?.slice(0,600),qa:window.__qa,ws:String(window.WebSocket).slice(0,150)})"))}
    private fun tap(s:ActivityScenario<MainActivity>,id:String){
        val point=JSONObject(js(s,"(()=>{const r=document.getElementById('$id').getBoundingClientRect();return {x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio}})()"))
        val offset=IntArray(2);s.onActivity{web(it.window.decorView)!!.getLocationOnScreen(offset)}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(offset[0]+point.getInt("x"),offset[1]+point.getInt("y"))
    }
    @Test fun realHostPairingAndNativeClient(){
        val args=InstrumentationRegistry.getArguments();val pairing=args.getString("pairing") ?: error("Pass isolated fixture pairing URL")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=Intent(context,MainActivity::class.java).putExtra("testPairing",pairing)
        ActivityScenario.launch<MainActivity>(intent).use {s->
            until(s,"Boolean(document.querySelector('#pair')?.onsubmit && document.querySelector('#code').value.length===43 && window.__HARNESS_NATIVE__)")
            js(s,"document.querySelector('#name').value='Android emulator QA';document.querySelector('#pair').requestSubmit();true")
            until(s,"Boolean(window.__DSH_TEST_PROBE__)",60000)
            js(s,"window.__qa={};fetch('/remote/session').then(r=>r.json()).then(v=>window.__qa.session=v);true")
            until(s,"window.__qa?.session?.state==='approved'")
            assertEquals("false",js(s,"document.cookie.includes('__Host-dsh_remote')"))
            js(s,"fetch('/api/session.list',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method:'session.list',payload:{}})}).then(r=>r.json()).then(v=>window.__qa.list=v);true")
            until(s,"Boolean(window.__qa.list)")
            js(s,"window.__rpc=async(method,payload)=>{const r=await fetch('/api/'+method,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method,payload})});const v=await r.json();if(!r.ok||!v.result?.ok)throw Error(JSON.stringify(v));return v.result.value;};__rpc('session.create',{agentPreset:'remote-test',cwd:'/tmp'}).then(v=>window.__qa.created=v);true")
            until(s,"Boolean(window.__qa.created?.sessionId)")
            js(s,"const ws=new WebSocket('wss://'+location.host+'/api/events.mux');window.__qa.wsState=ws.readyState;ws.onopen=()=>window.__qa.wsOpen=true;ws.onerror=()=>window.__qa.wsError=true;ws.onclose=e=>window.__qa.wsClose=e.code+':'+e.reason;ws.onmessage=e=>{window.__qa.ws=true;const f=JSON.parse(e.data);if(f.payload?.event?.type==='turn/end')window.__qa.ended=true};true")
            until(s,"window.__qa.wsOpen===true")
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.muzermat.harnessremote.debug android.permission.POST_NOTIFICATIONS").close()
            context.startForegroundService(Intent(context,AttentionService::class.java).putExtra("hostId",HostProfile.parsePairing(pairing).first.id))
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            val serviceDeadline=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<serviceDeadline && manager.activeNotifications.none{it.id==1 && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("持续提醒已开启")})Thread.sleep(200)
            assertTrue("Foreground reminder connected",manager.activeNotifications.any{it.id==1})
            js(s,"__rpc('session.prompt',{sessionId:__qa.created.sessionId,mode:'queue',content:[{type:'text',text:'Android native bridge streaming test'}]}).then(v=>window.__qa.prompt=v);true")
            until(s,"window.__qa.ended===true",60000)
            assertEquals("true",js(s,"window.__qa.ws===true"))
            val notificationDeadline=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<notificationDeadline && manager.activeNotifications.none{it.id==2})Thread.sleep(100)
            assertTrue("Generic completion notification",manager.activeNotifications.any{it.id==2 && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()=="打开 Harness Remote 查看"})
            context.stopService(Intent(context,AttentionService::class.java))
            js(s,"__rpc('session.history',{sessionId:__qa.created.sessionId,maxMessages:100}).then(v=>window.__qa.history=v);true")
            until(s,"Boolean(window.__qa.history?.events?.length)")
            js(s,"window.__shared=async(path,payload)=>{const r=await fetch('/remote/shared/'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({hostEpoch:__qa.session.hostEpoch,clientId:'android-qa',...payload})});const v=await r.json();if(!r.ok||v.error)throw Error(JSON.stringify(v));return v};(async()=>{const data=new TextEncoder().encode('Android file transfer. '.repeat(12000));const hash=[...new Uint8Array(await crypto.subtle.digest('SHA-256',data))].map(x=>x.toString(16).padStart(2,'0')).join('');const u=await __shared('upload/begin',{sessionId:__qa.created.sessionId,name:'android-qa.txt',type:'text/plain',size:data.length});for(let offset=0;offset<data.length;offset+=32768)await __shared('upload/chunk',{id:u.id,offset,data:btoa(String.fromCharCode(...data.subarray(offset,offset+32768)))});await __shared('upload/commit',{id:u.id,sha256:hash});let parts=[];for(let offset=0;offset<data.length;offset+=32768){const r=await __shared('upload/read',{id:u.id,sessionId:__qa.created.sessionId,offset});parts.push(Uint8Array.from(atob(r.data),c=>c.charCodeAt(0)))}const result=await new Blob(parts).arrayBuffer();__qa.fileVerified=[...new Uint8Array(await crypto.subtle.digest('SHA-256',result))].join(',')===[...new Uint8Array(await crypto.subtle.digest('SHA-256',data))].join(',') && result.byteLength===data.length;})().catch(e=>__qa.uploadError=e.message);true")
            until(s,"window.__qa.fileVerified===true",60000)
            js(s,"fetch('https://example.com/').then(()=>window.__qa.foreign='bad').catch(()=>window.__qa.foreign='blocked');true")
            until(s,"window.__qa.foreign==='blocked'")
            js(s,"Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Continue')?.click();const input=document.createElement('input');input.id='qa-file';input.type='file';input.accept='text/plain';input.style='position:fixed;left:20px;top:40px;width:240px;height:60px;z-index:2147483647;background:white';input.onchange=async()=>{__qa.pickerName=input.files[0]?.name;__qa.pickerText=await input.files[0]?.text();input.remove()};document.body.append(input);true")
            tap(s,"qa-file")
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("System document picker opened",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000))
            device.waitForIdle();device.findObject(By.desc("Show roots"))?.click();device.waitForIdle();Thread.sleep(400)
            device.findObject(By.res("android:id/title").text("Downloads"))?.click();device.waitForIdle();Thread.sleep(400)
            val selected=device.wait(Until.findObject(By.text("harness-remote-qa.txt")),5000)
            assertNotNull("Test file visible in Downloads",selected);selected.click()
            until(s,"window.__qa.pickerName==='harness-remote-qa.txt' && window.__qa.pickerText.includes('UTF-8')")
            js(s,"__shared('resource/open',{sessionId:__qa.created.sessionId,path:'harness-native-download.bin'}).then(r=>{const link=document.createElement('a');link.id='qa-download';link.href=r.url;link.download='harness-native-download.bin';link.textContent='Download QA';link.style='position:fixed;left:20px;top:40px;width:240px;height:60px;z-index:2147483647;background:white';document.body.append(link);__qa.downloadReady=true});true")
            until(s,"window.__qa.downloadReady===true")
            tap(s,"qa-download")
            assertTrue("System save picker opened",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000))
            val save=device.wait(Until.findObject(By.text(Pattern.compile("SAVE|Save|保存"))),5000)
            assertNotNull("Save action available",save);save.click()
            val savedDeadline=System.currentTimeMillis()+15000
            while(System.currentTimeMillis()<savedDeadline && !device.executeShellCommand("stat -c %s /sdcard/Download/harness-native-download.bin").contains("524288"))Thread.sleep(200)
            assertTrue("Native streaming download saved",device.executeShellCommand("stat -c %s /sdcard/Download/harness-native-download.bin").contains("524288"))
            // Activity recreation must restore authorization from encrypted storage, with no repeated claim.
            s.recreate()
            until(s,"Boolean(window.__DSH_TEST_PROBE__)",60000)
        }
    }
}
