package com.muzermat.harnessremote

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class MainActivity:ComponentActivity() {
    private lateinit var vault:Vault
    private lateinit var root:LinearLayout
    private var web:WebView?=null
    private var transport:PinnedTransport?=null
    private var bridge:NativeBridge?=null
    private val worker=Executors.newFixedThreadPool(3)
    private var generation=0
    private var files:ValueCallback<Array<Uri>>?=null
    private var downloadUrl:String?=null
    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){ uris -> files?.onReceiveValue(uris.toTypedArray());files=null }
    private val saveFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){ uri -> val url=downloadUrl;downloadUrl=null;if(uri!=null&&url!=null)download(url,uri) }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)startReminders()else Toast.makeText(this,"未开启通知，返回 App 时仍会同步任务",Toast.LENGTH_LONG).show()}
    private fun startReminders(){val host=transport?.host ?: return;androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,AttentionService::class.java).putExtra("hostId",host.id))}
    private fun reminders(){AlertDialog.Builder(this).setTitle("持续任务提醒").setMessage("保持一条到电脑的直连，收到任务结果或待确认事项时通知。会显示常驻通知；系统可能限制时长，进程被结束后不保证即时提醒。").setPositiveButton("开启"){_,_->if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else startReminders()}.setNeutralButton("停止"){_,_->stopService(Intent(this,AttentionService::class.java))}.setNegativeButton("取消",null).show()}
    private val scan=registerForActivityResult(ScanContract()){ result -> if(result.contents!=null)pair(result.contents) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);vault=Vault(this)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.background)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(web!=null)home()else{isEnabled=false;onBackPressedDispatcher.onBackPressed()}}})
        home()
        val restoreId=savedInstanceState?.getString("activeHost") ?: intent.getStringExtra("openHost")
        val testPairing=if(BuildConfig.DEBUG && savedInstanceState==null)intent.getStringExtra("testPairing")else null
        if(testPairing!=null)pair(testPairing)
        else runCatching {val hosts=vault.hosts();val restore=hosts.find{it.id==restoreId} ?: if(savedInstanceState==null)hosts.singleOrNull()else null;restore?.let{connect(it)}}.onFailure{error(it)}
    }
    private fun text(value:String,size:Float=18f)=TextView(this).apply {text=value;textSize=size;setTextColor(getColor(R.color.text));setPadding(20,16,20,16)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;setTextColor(getColor(R.color.text));minWidth=dp(48);minimumWidth=dp(48);minHeight=dp(44)
        background=android.graphics.drawable.GradientDrawable().apply{setColor(getColor(R.color.card));cornerRadius=dp(12).toFloat()}
        setPadding(dp(14),dp(8),dp(14),dp(8));layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(8),dp(4),dp(8),dp(4))};setOnClickListener{action()}
    }
    private fun error(t:Throwable) {AlertDialog.Builder(this).setTitle("连接未完成").setMessage(t.message ?: "请检查电脑地址和网络后重试").setPositiveButton("知道了",null).show()}
    private fun closePage(){generation++;bridge?.close();bridge=null;web?.apply{stopLoading();destroy()};web=null;transport?.close();transport=null;root.removeAllViews()}
    private fun home(){
        closePage()
        root.addView(text("Harness Remote",28f));root.addView(text("扫码连接电脑，继续同一个任务。无需域名或安装证书。",16f))
        root.addView(button("扫描电脑二维码"){scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑「手机远程」中的二维码").setBeepEnabled(false).setOrientationLocked(false))})
        root.addView(button("粘贴配对链接"){val input=EditText(this).apply {hint="https://[IPv6]:8443/remote/pair#…";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS};AlertDialog.Builder(this).setTitle("电脑配对链接").setView(input).setPositiveButton("连接"){_,_->pair(input.text.toString().trim())}.setNegativeButton("取消",null).show()})
        val scroll=ScrollView(this);val hosts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(hosts);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        try {for(host in vault.hosts()){
            hosts.addView(button(host.name+"\n"+host.origin){connect(host)})
            hosts.addView(button("管理 ${host.name}"){manage(host)})
        }}catch(e:Exception){error(e)}
        root.addView(button("检查更新 · ${BuildConfig.VERSION_NAME}"){checkUpdate()})
        root.addView(text("公网 IPv6 直连取决于两端网络和电脑入站规则。后台或进程结束后，返回 App 会重新同步电脑任务。",13f))
    }
    private fun manage(host:HostProfile){
        AlertDialog.Builder(this).setTitle(host.name).setItems(arrayOf("修改电脑地址","修改名称","忘记此电脑")){_,index->when(index){
            0->{val input=EditText(this).apply {setText(host.origin);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS};AlertDialog.Builder(this).setTitle("新的 IPv6 HTTPS 地址").setMessage("保留已配对公钥；其他电脑无法冒充此连接。").setView(input).setPositiveButton("校验并保存"){_,_->try{val updated=host.updateAddress(input.text.toString().trim());connect(updated)}catch(e:Exception){error(e)}}.setNegativeButton("取消",null).show()}
            1->{val input=EditText(this).apply{setText(host.name)};AlertDialog.Builder(this).setTitle("电脑名称").setView(input).setPositiveButton("保存"){_,_->vault.save(host.copy(name=input.text.toString().take(80).ifBlank{"我的电脑"}));home()}.setNegativeButton("取消",null).show()}
            2->AlertDialog.Builder(this).setTitle("忘记此电脑？").setMessage("清除本机授权。也可在电脑的设备列表撤销手机。").setPositiveButton("忘记"){_,_->if(AttentionService.activeHostId==host.id)stopService(Intent(this,AttentionService::class.java));vault.remove(host.id);home()}.setNegativeButton("取消",null).show()
        }}.show()
    }
    private fun pair(raw:String){try{val (host,url)=HostProfile.parsePairing(raw);connect(host,url)}catch(e:Exception){error(e)}}
    private fun connect(host:HostProfile,pairUrl:String?=null){
        closePage();val current=generation
        root.addView(text("正在验证电脑身份…",22f));root.addView(text(host.origin,14f));root.addView(button("返回"){home()})
        val client=PinnedTransport(host,{vault.cookie(host.id)},{vault.cookie(host.id,it)});transport=client
        worker.execute {try {
            client.verify()
            if(pairUrl==null){val session=client.session();check(session.optString("state")=="approved"){"设备尚未批准，请在电脑确认或重新配对"}}
            vault.save(host)
            runOnUiThread {if(current==generation)try{showPage(client,pairUrl ?: host.origin+"/")}catch(e:Exception){home();error(e)}}
        }catch(e:Exception){runOnUiThread{if(current==generation){home();error(IllegalStateException(diagnose(e),e))}}}}
    }
    private fun diagnose(e:Throwable):String = when {
        generateSequence(e){it.cause}.any{it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException}->"电脑身份或证书校验失败。请确认电脑地址和系统时间；电脑重装后需要重新扫码。"
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "无法到达电脑。请确认 Harness 正在运行、IPv6 地址未变化，以及手机网络和校园网允许该端口。"
        else -> e.message ?: "连接失败，请检查网络后重试"
    }
    @Suppress("SetJavaScriptEnabled")
    private fun showPage(client:PinnedTransport,url:String){
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){"请先更新 Android System WebView"}
        root.removeAllViews()
        val bar=LinearLayout(this);bar.addView(button("电脑"){home()},LinearLayout.LayoutParams(-2,-2));bar.addView(text(client.host.name,16f),LinearLayout.LayoutParams(0,-2,1f));bar.addView(button("提醒"){reminders()},LinearLayout.LayoutParams(-2,-2));bar.addView(button("重连"){web?.reload()},LinearLayout.LayoutParams(-2,-2));root.addView(bar)
        val view=WebView(this);web=view;root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        view.settings.apply {javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);mediaPlaybackRequiresUserGesture=true;cacheMode=WebSettings.LOAD_NO_CACHE}
        CookieManager.getInstance().setAcceptCookie(false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        bridge=NativeBridge(view,client){download,name->requestDownload(download,name)};WebViewCompat.addWebMessageListener(view,"HarnessNative",setOf(client.host.origin),bridge!!)
        WebViewCompat.addDocumentStartJavaScript(view,assets.open("native-transport.js").bufferedReader().use{it.readText()},setOf(client.host.origin))
        view.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(v:WebView,request:WebResourceRequest):Boolean {
                val parsed=request.url.toString().toHttpUrlOrNull()
                if(parsed!=null&&client.host.owns(parsed))return false
                if(request.isForMainFrame && request.hasGesture() && request.url.scheme in listOf("https","http","mailto"))runCatching {startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                return true
            }
            override fun shouldInterceptRequest(v:WebView,request:WebResourceRequest):WebResourceResponse {
                try {
                    if(request.method !in listOf("GET","HEAD"))return blocked("请通过安全连接提交请求")
                    var req=client.request(request.url.toString(),request.method,request.requestHeaders)
                    var response=client.client.newCall(req).execute();var redirects=0
                    while(response.code in 300..399){check(++redirects<=3);val next=response.header("Location") ?: error("无效跳转");val resolved=req.url.resolve(next) ?: error("无效跳转");response.close();req=client.request(resolved.toString());response=client.client.newCall(req).execute()}
                    val type=response.body?.contentType();val headers=response.headers.names().filter{it.lowercase() !in setOf("set-cookie","set-cookie2","content-length","content-encoding")}.associateWith{response.header(it) ?: ""}
                    return WebResourceResponse(if(type!=null)type.type+"/"+type.subtype else "application/octet-stream",type?.charset()?.name() ?: "UTF-8",response.code,response.message.ifBlank{"Response"},headers,response.body?.byteStream() ?: ByteArrayInputStream(byteArrayOf()))
                }catch(e:Exception){return blocked("连接中断，请返回电脑列表重新连接。")}
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel()}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{home();error(IllegalStateException("页面已停止，请重新连接；电脑任务会继续运行。"));return true}
        }
        view.webChromeClient=object:WebChromeClient(){
            override fun onShowFileChooser(v:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {files?.onReceiveValue(null);files=callback;picker.launch(params.acceptTypes.filter{it.isNotBlank()}.toTypedArray().ifEmpty{arrayOf("*/*")});return true}
            override fun onPermissionRequest(request:PermissionRequest){request.deny()}
        }
        view.setDownloadListener { download,_,disposition,mime,_ ->
            val parsed=download.toHttpUrlOrNull();if(parsed==null||!client.host.owns(parsed)){error(IllegalArgumentException("只能下载这台电脑的授权文件"));return@setDownloadListener}
            requestDownload(download,URLUtil.guessFileName(download,disposition,mime))
        }
        view.loadUrl(url)
    }
    private fun blocked(message:String)=WebResourceResponse("text/plain","UTF-8",502,"Connection unavailable",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(message.toByteArray()))
    private fun requestDownload(url:String,name:String){
        if(downloadUrl!=null){Toast.makeText(this,"请先完成当前保存操作",Toast.LENGTH_SHORT).show();return}
        transport?.request(url) ?: return
        val suggested=name.ifBlank{URLUtil.guessFileName(url,null,null)}.map{if(it.code<32 || it=='/' || it.code==92) '_' else it}.joinToString("").take(255)
        downloadUrl=url;saveFile.launch(suggested)
    }
    private fun download(url:String,uri:Uri){val client=transport ?: return;worker.execute {try{client.client.newCall(client.request(url)).execute().use{r->check(r.isSuccessful){"下载授权失效"};val input=r.body?.byteStream() ?: error("文件为空");contentResolver.openOutputStream(uri,"w")!!.use{output->input.use{it.copyTo(output)}}};runOnUiThread{Toast.makeText(this,"文件已保存",Toast.LENGTH_LONG).show()}}catch(e:Exception){runOnUiThread{error(e)}}}}
    private fun checkUpdate(){
        Toast.makeText(this,"正在检查发布版本…",Toast.LENGTH_SHORT).show()
        worker.execute{try{
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("https://api.github.com/repos/y38501148-max/dsh-mobile-remote/releases?per_page=20").header("Accept","application/vnd.github+json").build()).execute().use{r->check(r.isSuccessful){"无法读取更新信息"};val releases=org.json.JSONArray(r.body!!.string());val release=(0 until releases.length()).map{releases.getJSONObject(it)}.firstOrNull{it.getString("tag_name").startsWith("android-v")&&!it.optBoolean("draft")};runOnUiThread{val tag=release?.optString("tag_name") ?: "暂无安卓发布";AlertDialog.Builder(this).setTitle("软件更新").setMessage("当前 ${BuildConfig.VERSION_NAME}\n发布 $tag\n下载 APK 后由安卓确认安装；升级请保留同一签名。").setPositiveButton("打开下载页"){_,_->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/y38501148-max/dsh-mobile-remote/releases")))}.setNegativeButton("关闭",null).show()}}
        }catch(e:Exception){runOnUiThread{error(e)}}}
    }
    override fun onSaveInstanceState(outState:Bundle){transport?.host?.id?.let{outState.putString("activeHost",it)};super.onSaveInstanceState(outState)}
    override fun onResume(){super.onResume();web?.onResume();web?.evaluateJavascript("window.dispatchEvent(new Event('online'))",null)}
    override fun onPause(){web?.onPause();super.onPause()}
    override fun onDestroy(){closePage();files?.onReceiveValue(null);worker.shutdownNow();super.onDestroy()}
}
