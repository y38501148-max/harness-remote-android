# Harness Remote for Android

Android 10+ 客户端，通过公网 IPv6 地址连接电脑上的同一个 DeepSeek Harness Host。无需域名、VPN、云账号或手动安装证书。

**软件在[电脑插件仓库的 GitHub Releases](https://github.com/y38501148-max/dsh-mobile-remote/releases)下载。** 本仓库提供安卓源码；电脑插件在 [dsh-mobile-remote](https://github.com/y38501148-max/dsh-mobile-remote)。

## 使用

1. 电脑安装 0.2.1 或兼容版本的手机远程插件，在设置中开启 IPv6 直连，生成二维码。
2. 安装签名 APK，在 App 扫码或粘贴配对链接，电脑确认设备权限。
3. 继续现有会话。IPv6 变化时在电脑管理中修改地址，App 校验原来的公钥；地址无法到达时不会自动切换第三方代理。

右上角电脑菜单中的「任务提醒」可主动开启持续任务提醒。系统可能限制后台时长或结束进程；返回 App 后从电脑恢复任务。此版本没有 FCM/厂商推送，也不保证强制停止后收到通知。

手机网络必须有可达 IPv6，校园网和电脑必须允许入站端口。模拟器通过不能证明蜂窝跨网通过。真机验证结果以 Release 说明为准。

新版提供手机会话导航、工作区选择、键盘适配和只读连接诊断。先在同一 Wi-Fi 运行诊断，再关闭 Wi-Fi 使用移动数据比较结果。

## 结构与信任

- Kotlin 原生外壳：ZXing 扫码、电脑管理、系统文件选择/保存、更新入口、可选前台提醒服务。
- OkHttp：扫码固定 SHA-256 SPKI，检查证书有效期、自签名、IP SAN、Host ID 与协议。无 SSL-error 放行。
- AndroidX WebKit：文档开始安装受限 HTTP/WS 适配器，复用原生 Harness 客户端；精确 origin 和主框架检查。
- Keystore AES-GCM：设备 Cookie 只存密文，不传给 JavaScript，不进入系统备份。
- 会话和任务留在电脑，App 不运行模型、不复制 Host。

原生桥限定请求方法和目的地，保留错误/取消/请求 ID，API 按块传输；授权资源流式读取。外部链接仅在用户手势下交给系统浏览器。更新通过固定的插件仓库 Releases 下载，由安卓系统确认安装与签名。

## 构建与测试

JDK 17+、Android SDK 36；Gradle Wrapper 固定 8.14.3，AGP 8.11.0、Kotlin 2.0.21。设置 `ANDROID_HOME` 或本地 `local.properties` 后：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

测试公钥错误、过期证书、IP/域名不匹配、证书续期身份不变、QR 编码和过期、跨 origin 拒绝。端到端测试使用插件仓库的隔离真实 Host 和模拟模型：

```sh
# 在电脑插件仓库启动：
node scripts/android-fixture.mjs
# 回到安卓源码仓库，连接 Android 模拟器后：
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/run-emulator-test.py
python3 scripts/run-mobile-ui-test.py
```

`run-emulator-test.py` 仅清除 `.debug` 应用数据。签名包验收另用 `python3 scripts/run-signed-smoke.py`；它在测试模拟器中清除发行包数据、通过正常粘贴配对界面连接，再验证覆盖安装和进程重启。两个脚本默认设备为 `emulator-5554`，日常手机不应作为清空数据的测试目标。测试二维码通过 debug-only Intent 传入，发行版忽略该入口；隔离 Host 的待批准设备由测试 helper 自动批准，生产插件仍需电脑手动批准。

## 签名与发布

发布脚本读取受限目录中的 PKCS12/JKS 密钥；不要提交密钥、密码或 `local.properties`。

```sh
export HARNESS_SIGNING_STORE=/private/path/harness-remote.jks
export HARNESS_SIGNING_PASSWORD=...
./gradlew :app:assembleRelease
```

Alias 为 `harness-remote`。同一应用升级保持同一密钥；versionCode 必须递增。发布环境应独立备份签名密钥与密码，丢失密钥后不能更新已有安装。

APK、SHA256SUMS 和 manifest 发布到 `y38501148-max/dsh-mobile-remote`，tag `android-v<version>`。manifest 同时记录本仓库与电脑插件提交、协议、最低 Android、APK 哈希和签名证书哈希。签名预览版以 prerelease 发布；没有真机和跨网验证的项目不声明通过。
