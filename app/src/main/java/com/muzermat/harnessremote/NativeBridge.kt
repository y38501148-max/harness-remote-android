package com.muzermat.harnessremote

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Only the exact pinned origin's main frame receives this message bridge. */
class NativeBridge(private val view:WebView, private val transport:PinnedTransport,private val download:(String,String)->Unit): WebViewCompat.WebMessageListener {
    private data class Upload(val meta:JSONObject,val bytes:ByteArrayOutputStream=ByteArrayOutputStream())
    private val uploads=ConcurrentHashMap<String,Upload>()
    private val calls=ConcurrentHashMap<String,Call>()
    private val sockets=ConcurrentHashMap<String,WebSocket>()
    @Volatile private var closed=false
    private fun reply(proxy:JavaScriptReplyProxy,value:JSONObject) {view.post { if(!closed)proxy.postMessage(value.toString()) }}
    override fun onPostMessage(webView:WebView,message:WebMessageCompat,sourceOrigin:Uri,isMainFrame:Boolean,replyProxy:JavaScriptReplyProxy) {
        if(closed || !isMainFrame || sourceOrigin.toString().trimEnd('/')!=transport.host.origin) return
        var id=""
        try {
            val raw=message.data ?: return
            require(raw.length<=150_000) { "原生消息过大" }
            val data=JSONObject(raw);id=data.getString("id");require(id.matches(Regex("[0-9]{1,12}")))
            when(data.getString("type")) {
                "download" -> {val url=data.getString("url");transport.request(url);download(url,data.optString("name","").take(255))}
                "http-begin" -> {require(uploads.size+calls.size<32);require(!uploads.containsKey(id)&&!calls.containsKey(id));transport.request(data.getString("url"),data.getString("method"));uploads[id]=Upload(data)}
                "http-body" -> {val upload=uploads[id] ?: error("Missing upload");val bytes=Base64.decode(data.getString("body"),Base64.NO_WRAP);require(upload.bytes.size()+bytes.size<=32*1024*1024);upload.bytes.write(bytes)}
                "http-send" -> {
                    val upload=uploads.remove(id) ?: error("Missing request")
                    val headers=upload.meta.optJSONObject("headers") ?: JSONObject()
                    val req=transport.request(upload.meta.getString("url"),upload.meta.getString("method"),headers.keys().asSequence().associateWith { headers.getString(it) },upload.bytes.toByteArray())
                    val call=transport.client.newCall(req);calls[id]=call
                    call.enqueue(object:Callback {
                        override fun onFailure(call:Call,e:java.io.IOException) {calls.remove(id);reply(replyProxy,JSONObject().put("type","error").put("id",id).put("error","连接中断，请核对操作结果后重试"))}
                        override fun onResponse(call:Call,response:Response) {
                            try {response.use { r ->
                                require(r.code !in 300..399) { "不允许请求重定向" }
                                val responseHeaders=JSONObject();r.headers.names().filter { it.lowercase() !in setOf("set-cookie","set-cookie2") }.forEach { responseHeaders.put(it,r.header(it)) }
                                reply(replyProxy,JSONObject().put("type","http-head").put("id",id).put("status",r.code).put("message",r.message).put("headers",responseHeaders))
                                r.body?.byteStream()?.use { input -> val buffer=ByteArray(65536);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=32*1024*1024);reply(replyProxy,JSONObject().put("type","http-chunk").put("id",id).put("body",Base64.encodeToString(buffer,0,n,Base64.NO_WRAP)))} }
                                reply(replyProxy,JSONObject().put("type","http-end").put("id",id))
                            }} catch(e:Exception) {reply(replyProxy,JSONObject().put("type","error").put("id",id).put("error","响应读取失败，请重新连接"))} finally {calls.remove(id)}
                        }
                    })
                }
                "cancel" -> {uploads.remove(id);calls.remove(id)?.cancel()}
                "ws-open" -> {
                    require(sockets.size<8 && !sockets.containsKey(id))
                    val url=data.getString("url");require(url.startsWith("wss://"))
                    val builder=transport.request("https://"+url.removePrefix("wss://")).newBuilder()
                    val protocols=data.optJSONArray("protocols");if(protocols!=null&&protocols.length()>0){val values=(0 until protocols.length()).map { protocols.getString(it) };require(values.all { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) });builder.header("Sec-WebSocket-Protocol",values.joinToString(", "))}
                    val ws=transport.client.newWebSocket(builder.build(),object:WebSocketListener(){
                        override fun onOpen(webSocket:WebSocket,response:Response){reply(replyProxy,JSONObject().put("type","ws-open").put("id",id).put("protocol",response.header("Sec-WebSocket-Protocol", "")))}
                        override fun onMessage(webSocket:WebSocket,text:String){reply(replyProxy,JSONObject().put("type","ws-message").put("id",id).put("body",text).put("binary",false))}
                        override fun onMessage(webSocket:WebSocket,bytes:ByteString){reply(replyProxy,JSONObject().put("type","ws-message").put("id",id).put("body",bytes.base64()).put("binary",true))}
                        override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
                        override fun onClosed(webSocket:WebSocket,code:Int,reason:String){sockets.remove(id);reply(replyProxy,JSONObject().put("type","ws-close").put("id",id).put("code",code).put("reason",reason).put("clean",true))}
                        override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){if(BuildConfig.DEBUG)android.util.Log.w("HarnessTransport","WebSocket failed",t);sockets.remove(id);response?.close();reply(replyProxy,JSONObject().put("type","error").put("id",id).put("error","实时连接中断"))}
                    });sockets[id]=ws
                }
                "ws-send" -> {val ws=sockets[id] ?: error("Socket closed");val body=data.getString("body");require(body.length<=16_384);if(data.optBoolean("binary"))ws.send(Base64.decode(body,Base64.NO_WRAP).toByteString())else ws.send(body)}
                "ws-close" -> {val code=data.optInt("code",1000);require(code==1000||code in 3000..4999);sockets[id]?.close(code,data.optString("reason",""))}
                else -> error("Unsupported operation")
            }
        } catch(e:Exception) {uploads.remove(id);calls.remove(id)?.cancel();reply(replyProxy,JSONObject().put("type","error").put("id",id).put("error",e.message ?: "请求失败"))}
    }
    fun close(){closed=true;uploads.clear();calls.values.forEach {it.cancel()};calls.clear();sockets.values.forEach {it.cancel()};sockets.clear()}
}
