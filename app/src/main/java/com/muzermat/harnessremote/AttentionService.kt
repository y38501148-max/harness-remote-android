package com.muzermat.harnessremote

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors

/** Opt-in, visible foreground reminders. No cloud account or push relay. */
class AttentionService:Service(){
    companion object {@Volatile var activeHostId:String?=null;private set}
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var client:PinnedTransport?=null
    private var socket:WebSocket?=null
    private var generation=0
    private var retryMs=2000L
    private var host:HostProfile?=null
    private var lastAlert=0L
    private val manager get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate();manager.createNotificationChannel(NotificationChannel("connection","持续连接提醒",NotificationManager.IMPORTANCE_LOW));manager.createNotificationChannel(NotificationChannel("attention","任务需要关注",NotificationManager.IMPORTANCE_DEFAULT))}
    private fun openIntent():PendingIntent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("openHost",host?.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun connection(message:String)=NotificationCompat.Builder(this,"connection").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Harness Remote · ${host?.name ?: "电脑"}").setContentText(message).setContentIntent(openIntent()).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"停止提醒",PendingIntent.getService(this,1,Intent(this,AttentionService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        val vault=Vault(this)
        host=runCatching{vault.hosts().find{it.id==intent?.getStringExtra("hostId")}}.getOrNull()
        startForeground(1,connection("正在连接电脑；系统可能限制后台时长"))
        val selected=host ?: run{stopSelf();return START_NOT_STICKY}
        activeHostId=selected.id
        generation++;handler.removeCallbacksAndMessages(null);socket?.cancel();client?.close()
        client=PinnedTransport(selected,{vault.cookie(selected.id)},{vault.cookie(selected.id,it)})
        connect(generation)
        return START_NOT_STICKY
    }
    private fun connect(current:Int){
        val transport=client ?: return
        worker.execute{
            try{
                transport.verify();check(transport.session().optString("state")=="approved"){"设备授权失效"}
                if(current!=generation)return@execute
                socket=transport.client.newWebSocket(transport.request("/api/events.mux"),object:WebSocketListener(){
                    override fun onOpen(ws:WebSocket,response:Response){handler.post{if(current==generation){retryMs=2000;manager.notify(1,connection("持续提醒已开启；点击返回会话"))}}}
                    override fun onMessage(ws:WebSocket,text:String){event(current,text)}
                    override fun onMessage(ws:WebSocket,bytes:ByteString){event(current,bytes.utf8())}
                    override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
                    override fun onClosed(ws:WebSocket,code:Int,reason:String){reconnect(current)}
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){response?.close();reconnect(current)}
                })
            }catch(e:Exception){
                if(e is IllegalStateException || e is javax.net.ssl.SSLException)handler.post{if(current==generation){manager.notify(2,NotificationCompat.Builder(this,"attention").setSmallIcon(R.drawable.ic_launcher).setContentTitle("电脑连接需要重新确认").setContentText("打开 Harness Remote 检查电脑身份或设备授权").setContentIntent(openIntent()).setAutoCancel(true).build());stopSelf()}}
                else reconnect(current)
            }
        }
    }
    private fun reconnect(current:Int){handler.post{if(current!=generation)return@post;manager.notify(1,connection("连接中断，正在恢复；返回 App 可查看状态"));val delay=retryMs;retryMs=(retryMs*2).coerceAtMost(60000);handler.postDelayed({if(current==generation)connect(current)},delay)}}
    private fun event(current:Int,text:String){
        if(current!=generation)return
        val p=runCatching{JSONObject(text).optJSONObject("payload")}.getOrNull() ?: return
        val title=when{p.optString("type") in listOf("approval/requested","question/requested")->"电脑任务需要确认";p.optString("type")=="session/event"&&p.optJSONObject("event")?.optString("type")=="turn/end"->"电脑任务有新结果";else->return}
        handler.post{if(current==generation && System.currentTimeMillis()-lastAlert>3000){lastAlert=System.currentTimeMillis();manager.notify(2,NotificationCompat.Builder(this,"attention").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText("打开 Harness Remote 查看").setContentIntent(openIntent()).setAutoCancel(true).build())}}
    }
    override fun onTimeout(startId:Int,fgsType:Int){stopSelf()}
    override fun onDestroy(){activeHostId=null;generation++;handler.removeCallbacksAndMessages(null);socket?.cancel();client?.close();worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
