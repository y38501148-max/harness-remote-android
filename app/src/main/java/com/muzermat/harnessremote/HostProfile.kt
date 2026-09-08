package com.muzermat.harnessremote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** The scanned public key is the identity; a reachable address is never sufficient trust. */
data class HostProfile(val id: String, val origin: String, val pin: String, val name: String = "我的电脑") {
    val base: HttpUrl get() = origin.toHttpUrlOrNull() ?: error("连接地址无效")
    fun json() = JSONObject().put("id",id).put("origin",origin).put("pin",pin).put("name",name)
    fun owns(url: HttpUrl) = url.scheme == "https" && url.host == base.host && url.port == base.port && url.username.isEmpty() && url.password.isEmpty()
    fun updateAddress(value: String): HostProfile {
        val url = value.toHttpUrlOrNull() ?: error("请输入完整 HTTPS IPv6 地址")
        require(validOrigin(url)) { "需要 HTTPS 地址，不能包含用户名、路径或查询参数" }
        return copy(origin=url.toString().trimEnd('/'))
    }
    companion object {
        fun validOrigin(url: HttpUrl) = url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath=="/" && url.query==null && url.fragment==null
        fun fromJson(v: JSONObject): HostProfile {
            val p=HostProfile(v.getString("id"),v.getString("origin"),v.getString("pin"),v.optString("name","我的电脑"))
            require(validOrigin(p.base) && p.id.matches(Regex("[a-f0-9-]{36}")) && Base64.getDecoder().decode(p.pin).size==32) { "电脑身份数据无效" }
            return p
        }
        fun parsePairing(raw: String, now: Long = System.currentTimeMillis()): Pair<HostProfile,String> {
            require(raw.length<=4096) { "二维码过长" }
            val url=raw.toHttpUrlOrNull() ?: error("请扫描 Harness 手机远程二维码")
            require(url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath=="/remote/pair" && url.query==null) { "二维码连接地址无效" }
            val values=(url.encodedFragment ?: "").split('&').associate { part -> val kv=part.split('=',limit=2); require(kv.size==2); URLDecoder.decode(kv[0],"UTF-8") to URLDecoder.decode(kv[1],"UTF-8") }
            require(values["v"]=="1") { "请升级电脑端手机远程插件并重新生成二维码" }
            val expires=values["expires"]?.toLongOrNull() ?: error("二维码缺少有效期")
            require(expires>now && expires<=now+5*60_000) { "二维码已过期或时间不正确，请重新生成" }
            val invite=values["invite"] ?: error("缺少配对邀请")
            require(invite.matches(Regex("[A-Za-z0-9_-]{43}"))) { "配对邀请格式无效" }
            val profile=fromJson(JSONObject().put("id",values["hostId"]).put("pin",values["pin"]).put("origin",url.newBuilder().encodedPath("/").fragment(null).build().toString().trimEnd('/')))
            return profile to raw
        }
    }
}
