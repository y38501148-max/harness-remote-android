package com.muzermat.harnessremote

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

class PinnedTrust(private val pin: String): X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client trust is not supported") }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert=chain?.firstOrNull() ?: throw CertificateException("Missing computer identity")
        cert.checkValidity()
        val actual=MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        if (!MessageDigest.isEqual(actual,Base64.getDecoder().decode(pin))) throw CertificateException("Computer identity changed")
        // Our computer certificate is self-signed. Validate the signature, not merely the string fingerprint.
        cert.verify(cert.publicKey)
    }
}
class PinnedTransport(val host: HostProfile, private val loadCookie:()->String, private val saveCookie:(String)->Unit) {
    private val trust=PinnedTrust(host.pin)
    private val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
    val client: OkHttpClient = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust)
        // Default hostname verifier remains enabled: the leaf SAN must match the IPv6/IP hostname.
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(12,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS)
        .cookieJar(object: CookieJar {
            override fun loadForRequest(url:HttpUrl):List<Cookie> = if(host.owns(url)) loadCookie().split('\n').mapNotNull { Cookie.parse(url,it) }.filter { it.expiresAt>System.currentTimeMillis() } else emptyList()
            override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {
                if(host.owns(url)) cookies.find { it.name=="__Host-dsh_remote" && it.secure && it.httpOnly && it.hostOnly && it.path=="/" }?.let { saveCookie(it.toString()) }
            }
        }).build()
    // Media and saved files may outlive an API call; stalled reads still time out.
    val resourceClient:OkHttpClient=client.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    fun request(url:String,method:String="GET",headers:Map<String,String> = emptyMap(),body:ByteArray?=null):Request {
        val resolved=host.base.resolve(url) ?: error("无效的请求地址")
        require(host.owns(resolved)) { "拒绝向其他地址发送电脑授权" }
        require(method in listOf("GET","HEAD","POST")) { "不支持的请求方法" }
        val builder=Request.Builder().url(resolved).header("Origin",host.origin).header("X-DSH-Mobile-Protocol","1")
        for((name,value) in headers) if(name.lowercase() in setOf("content-type","accept","range","x-dsh-host-epoch","x-dsh-command-id")) builder.header(name,value)
        builder.method(method,if(method=="POST") (body ?: byteArrayOf()).toRequestBody(headers.entries.find { it.key.equals("content-type",true) }?.value?.toMediaTypeOrNull()) else null)
        return builder.build()
    }
    fun verify(using:OkHttpClient=client):JSONObject {
        using.newCall(request("/remote/info")).execute().use { response ->
            check(response.isSuccessful) { "电脑插件无法响应，请更新到支持安卓的版本" }
            val info=JSONObject(response.body?.string() ?: "{}")
            check(info.optString("hostId")==host.id) { "电脑身份已改变，请在电脑重新配对" }
            check(info.optInt("appTransportVersion")==1 && info.optInt("protocolVersion")==1) { "电脑插件协议不兼容，请更新软件" }
            return info
        }
    }
    fun session():JSONObject = client.newCall(request("/remote/session")).execute().use { r -> if(r.code==401||r.code==403) throw IllegalStateException("授权失效，请重新扫码配对");JSONObject(r.body?.string() ?: "{}") }
    fun close() {closer.execute {client.dispatcher.cancelAll();client.connectionPool.evictAll()}}
    companion object {private val closer=java.util.concurrent.Executors.newSingleThreadExecutor()}
}
