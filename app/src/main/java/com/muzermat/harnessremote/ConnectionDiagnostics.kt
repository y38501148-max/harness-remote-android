package com.muzermat.harnessremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** User-requested, read-only probes of the selected computer. No credentials or third-party probes. */
class ConnectionDiagnostics(private val context:Context) {
    fun run(host:HostProfile):String {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.activeNetwork ?: return "手机网络：未连接\n\n请先开启 Wi-Fi 或移动数据，再重新诊断。"
        val capabilities=manager.getNetworkCapabilities(network)
        val links=manager.getLinkProperties(network)
        val kind=when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true->"VPN / 代理网络"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"移动数据"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"Wi-Fi"
            else->"其他网络"
        }
        val hasV6=links?.linkAddresses?.any {it.address is Inet6Address && !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress}==true
        val lines=mutableListOf("手机网络：$kind", "IPv6 地址：${if(hasV6) "已检测到" else "未检测到（VPN 可能另行提供路由）"}")
        if(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)!=true) lines.add("系统尚未确认互联网连通，可能需要先登录网络。")
        val start=android.os.SystemClock.elapsedRealtime()
        try {
            Socket().use {it.connect(InetSocketAddress(host.base.host,host.base.port),5000)}
            lines.add("电脑端口：可达（${android.os.SystemClock.elapsedRealtime()-start} ms）")
        } catch(e:Exception) {
            lines.add("电脑端口：未连通")
            lines.add(when {
                host.base.host.contains(':') && !hasV6 -> "\n当前手机网络未检测到 IPv6。先切换到支持 IPv6 的移动数据或 Wi-Fi 再试；如使用 VPN，请检查其 IPv6 路由。"
                e is java.net.SocketTimeoutException -> "\n连接超时。可能是校园网入站限制、路由问题、电脑休眠或防火墙丢弃连接；仅凭超时不能确定是哪一项。"
                e is java.net.ConnectException -> "\n连接被拒绝或未能建立。请先确认电脑上的 Harness 与远程入口已开启，地址和端口没有变化。"
                else -> "\n没有建立到电脑的连接。请检查地址、电脑网络和手机路由。"
            })
            lines.add("\n排查顺序：先在同一 Wi-Fi 下测试，再关闭 Wi-Fi 用移动数据测试。若仅校外失败，需要检查校园网入站规则；当前没有部署备用中继。")
            return lines.joinToString("\n")
        }
        val transport=PinnedTransport(host,{""},{})
        try {
            val info=transport.verify(transport.client.newBuilder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build())
            lines.add("电脑公钥与证书：验证通过")
            lines.add("Harness 插件：${info.optString("pluginVersion","已响应")}")
            lines.add("\n此刻可以通过 $kind 到达这台电脑。诊断未读取会话，也未修改授权；回到连接页面继续配对或任务。")
            if(kind=="Wi-Fi")lines.add("\n要验证校外连接，请关闭 Wi-Fi 后再运行一次。")
        } catch(e:Exception) {
            val trustError=generateSequence<Throwable>(e){it.cause}.any {it is SSLException || it is java.security.cert.CertificateException}
            lines.add(if(trustError) "电脑身份：验证失败。端口可达，但证书、公钥或地址不匹配；请核对系统时间及电脑二维码。" else "Harness 验证：未通过。端口可达，请确认电脑插件版本、身份和服务状态。")
        } finally {transport.close()}
        return lines.joinToString("\n")
    }
}
