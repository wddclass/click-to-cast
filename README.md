# 投屏点我

安卓电视上的轻量 DLNA 接收端。手机和电视在同一个局域网里时，视频 App 的投屏列表里会看到一台叫 **投屏点我** 的设备。选中之后，电视用 AndroidX Media3 播放对方发来的地址。

没有账号、广告或统计。应用不提供片源，只接收发送端给出的播放地址。

出厂设备名就是「投屏点我」。设置里可以改成别的名字，但不会自带「客厅」「卧室」这类前缀，避免手机列表里出现含糊的名字。名字、开机自启、常显网速和浏览器标识改完就生效，没有单独的保存按钮。

## 能做什么

- SSDP 发现，以及 UPnP MediaRenderer（DMR）描述。
- AVTransport：`SetAVTransportURI`、`Play`、`Pause`、`Stop`、`Seek`、`GetPositionInfo`、`GetTransportInfo`、`GetMediaInfo`，以及能力、播放模式和当前可用动作查询。
- RenderingControl：主声道音量和静音。
- ConnectionManager：`GetProtocolInfo`、当前连接查询。
- GENA 事件：状态变化时向已订阅的控制点发送 `LastChange`。
- 播放 HTTP(S) 渐进式 MP4、HLS、DASH，以及 ExoPlayer 能识别的 FLV。地址被标错时，会先读一小段内容再判断是不是 HLS / DASH / FLV。
- 可从 DIDL 元数据里带上 `User-Agent`、`Referer` 等请求头。
- 前台服务常驻，可开机自启，回到桌面后仍然可被发现。
- 遥控器：上下键显示控制条。控制条显示时左右移动焦点；进度条获得焦点后左右才快进 10 秒。控制条隐藏时左右直接快进。倍速在按钮上方弹出，上下选择，OK 确认。音量键交给电视系统。返回先关掉倍速和控制条；控制条已经隐藏时，第一次返回在画面顶部提示「再按一次返回退出」，两秒内再按才退出。平板上可以拖动进度条，松手后才跳转。
- 倍速：0.5、0.75、1、1.25、1.5、2。暂停后再播放会留在原来的位置和倍速；新的投屏会停掉上一个，并把倍速改回 1。直播不提供倍速，选了也会被拒绝。
- 播放条在播放约 4 秒后自动隐藏，暂停、出错或打开倍速菜单时保持显示。点画面可以重新打开。控制条出现时，右上角显示当前下载速度（例如 2.3 MB/s），按播放器这一秒实际收到的网络字节计算。设置里打开「常显网速」后，控制条隐藏时也显示。常显胶囊是 14sp、10dp 圆角，底色更透。
- 直播（播放器判定为直播、HLS 播放列表没有 ENDLIST、已经就绪但没有正时长，或 DLNA 元数据时长为 0 / NOT_IMPLEMENTED 且播放器还没得到长度）只在控制条出现时，于进度原来的位置显示红点和 Live。控制条隐藏后，直播标记也一起消失。进度条、时间和倍速都会藏起来，快进和倍速也不再生效。右上角网速是磨砂玻璃胶囊（`bg_frosted`）：Android 12 及以上会模糊底板，Android 11 用半透明底和细边。之后的胶囊沿用这一套。常显网速用更紧的 `bg_frosted_compact`。
- 屏幕镜像：iPhone 先发不带 streams 的 `SETUP`（`ekey` / `eiv`，常带 `isScreenMirroringSession` 和 `combinedGetInfoWithControlSetup`）。电视只回 `timingPort` 和 `eventPort` 0，不编造 type 110。随后的 `GET /info`（没有 qualifier）带 `statusFlags` 68、1920×1080 的 `displays`，以及 type 100/101 的音频格式。下一次 `SETUP` 才带 type 110，可以走另一条 TCP、用会话 URL，这时才开全屏 H.264。加密视频按连续 AES-CTR 解开（byte 5 为 0x10 的是 IDR，不重置计数器），SPS/PPS 接到下一帧前面，长度前缀改成 Annex-B 再送进解码器。byte 4 为 5 的是每秒一次的传输报告，不解密。竖屏画面按比例居中。拆 type 96 音频时画面留着；type 110 或没有指定流的 `TEARDOWN`、以及返回键，才会关掉画面。镜像音频是 type 96，单独占用 UDP 数据端口和控制端口。AAC-ELD（ct=8，44100 立体声，csd `F8E85000`）和 AAC-LC（ct=4）用 AES-CBC 解开 RTP 负载（密钥是 playfair 解开后再和 X25519 共享密钥做 SHA-512 的前 16 字节，IV 是 `eiv`，每包重置），经 MediaCodec 解成 PCM，用 AudioTrack 播放，并留一个小抖动缓冲，大致跟画面对齐。`SET_PARAMETER` 的 volume（-144 到 0 dB）会改音量。ALAC（ct=2）只记录日志。NTP 从第一次 `SETUP` 的 timing 端口开始发。手机点「停止镜像」常常不发 `TEARDOWN`：控制连接被关掉或重置、镜像数据连接结束、大约 4.5 秒没有画面也没有 `/feedback`、计时端口也静下来，或对方发了 mDNS goodbye，都会关掉镜像回到首页，logcat 写明原因。另一条连接上的 `TEARDOWN` 同样生效。镜像时按上、下、OK 或菜单会弹出磨砂条（约 4 秒后收起），显示「正在镜像 · 设备名」、分辨率、实时网速和「结束镜像」，没有快进和倍速。返回先收起这条；已经收起时，顶部提示「再按一次返回退出」，再按才退出。
- 回到桌面后如果已经允许「显示在其他应用上层」，新的投屏会尝试自动打开播放画面；系统拦住时会有一条可点的通知。没有这个权限时，首页和设置里会说明怎么开。

4K 和 HDR 走电视自己的硬解码。支持与否取决于固件和片源是否加密，应用不会强行软解。

## 在 TCL 雷鸟上安装

目标机型参考：雷鸟鹏 6 SE（Amlogic T982，Android 11，界面 1920×1080）。安装包带 `armeabi-v7a` 和 `arm64-v8a` 的 playfair 原生库。雷鸟鹏 6 SE 是 32 位，必须装进含 `armeabi-v7a` 的包。

这台电视的固件经常拦截 `adb install`（未授权、`INSTALL_FAILED_USER_RESTRICTED`，或根本没有稳定的调试端口）。不要依赖 adb，用 U 盘或浏览器。

1. 从 [GitHub Releases](https://github.com/wddclass/click-to-cast/releases) 下载 `ClickToCast-0.1.0-release.apk`，并对照同目录的 `SHA256SUMS`。
2. 把 APK 拷到 FAT32 U 盘的根目录。
3. 电视上打开安装外部应用的开关。雷鸟系统里常见位置是「设置 → 系统 / 更多设置 → 安全 → 未知来源」，或「设置 → 设备偏好设置 → 安全与限制」。不同固件文案不一样。
4. 用电视自带的文件管理或媒体中心打开 U 盘里的 APK，按提示安装。
5. 如果系统没有文件管理器，可以在电脑上进入 APK 所在目录执行 `python3 -m http.server 8000`，电视浏览器打开 `http://电脑IP:8000/` 下载后再安装。
6. 装好后到「我的应用 / 全部应用」里打开「投屏点我」。部分雷鸟桌面不会把侧载应用放在首页推荐位，但应用列表里应该有。

打开后确认屏幕中央的大字是「投屏点我」，下面有电视的局域网 IP。手机连同一个 Wi-Fi（不要用访客网络，也不要开 AP 隔离），在 Bilibili、腾讯视频、爱奇艺、优酷、芒果 TV，或其他带标准 DLNA 的 App 里点投屏，选择「投屏点我」。

## 从源码构建

需要 JDK 17 或 21，以及 Android SDK Platform 35、Build Tools 35.0.0。

```sh
export JAVA_HOME=...
export ANDROID_HOME=...
./gradlew test assembleRelease
```

签名使用仓库里的调试密钥 `signing/clicktocast-debug.keystore`（别名 `clicktocast`，口令 `android`）。这是公开的测试密钥，方便覆盖安装，不能当作正式发布身份。谁都可以用它签出同包名的包。

产物在 `app/build/outputs/apk/release/ClickToCast-0.1.0-release.apk`。

推送标签 `v*` 时，GitHub Actions 会跑单元测试、打 release 包，并把 APK 和校验值挂到对应的 GitHub Release。

## 已知限制

- DLNA / UPnP 之外，另有 AirPlay 视频地址播放。应用在每块 IPv4 网卡上直接向 `224.0.0.251:5353` 宣告 `_airplay._tcp` 和 `_raop._tcp`（TTL 255），并回答同网段的 mDNS 查询。IP 变化或改名后会先发 TTL 0 的 goodbye，再按新地址重新宣告。iPhone 的 `GET /info` 是 **RTSP/1.0**，响应是 `RTSP/1.0 200`、二进制 plist，`deviceID` / `pk` / `features`（`0x5A7FFFF7,0x1E` = `130367356919`，与 versionCode 6 相同，含屏幕位和音频位）/ `model=AppleTV3,2` / `sourceVersion` 与 mDNS TXT 一致。`pk` 是这台电视自己的 Ed25519 公钥。同一条 RTSP 连接会继续读下一条请求。手机把视频 URL POST 到 `/play` 后由同一播放器开播。`/reverse` 带 `Upgrade: PTTH/1.0` 时回 `101` 并保持连接。
- iOS 26 在 `GET /info` 之后会发 `POST /pair-setup`，正文是 32 字节的 Ed25519 公钥。电视回自己的 32 字节公钥（和 mDNS / `/info` 里的 `pk` 是同一把），再完成 `pair-verify`（X25519 临时密钥 + Ed25519 签名，AES-128-CTR）。配对成功后，iOS 26 还会发 `POST /fp-setup`。16 字节的第一阶段按 mode 回 142 字节，164 字节的第二阶段回 12 字节头加请求最后 20 字节。第一次 `SETUP`（带 `ekey` / `eiv`，没有 streams）只回 `timingPort` 和 `eventPort` 0。没有 qualifier 的 `GET /info` 含 `statusFlags` 68 和 `displays`。第二次 `SETUP` 才带 `streams`（type 110，之后可能有 type 96），这时只回 `streams` 里的 `dataPort`。同一端口继续接受新的 TCP，按会话 URL 复用已经解开的密钥。`RECORD` 带 `Audio-Latency: 11025` 和 `Audio-Jack-Status: connected; type=analog`。`GET_PARAMETER` 的 volume 回 `volume: 0.000000\r\n`。`SET_PARAMETER` 回 200。Safari、照片、YouTube 的视频地址仍走明文 `POST /play`。这不是 HomeKit SRP，也没有 ChaCha20-Poly1305。`features` 仍是 versionCode 6 的 `0x5A7FFFF7,0x1E`。屏幕镜像会调用随包附带的 GPL playfair（`playfair_decrypt`）解开 `ekey`，再按 UxPlay 的 SHA-512（`aeskey` 与 pair-verify 的 X25519 共享密钥，然后是 `AirPlayStreamKey` / `AirPlayStreamIV` 加 `streamConnectionID`）得到 AES-128-CTR 密钥，解密 128 字节头后面的 H.264。type 110 或未指定流的 `TEARDOWN` 关闭镜像画面，type 96 不关。logcat 标签 `ClickToCast`。RAOP 音频只做 type 96 的 UDP 接收，AAC-ELD / AAC-LC 会尝试硬解。
- Miracast、Chromecast、乐播 / 乐联私有协议仍未实现。
- 爱奇艺、优酷、腾讯视频、Bilibili 的部分版本只走私有协议，或者地址带短时签名和 DRM。它们走标准 DLNA 时可以被发现；走私有协议时列表里不会出现本应用，或者给出地址后无法播放。
- 不转码。编码、封装或 DRM 电视硬解不了，就会直接失败。
- 控制面没有配对。请只在自己的家庭网络里使用，不要把 8192 端口映射到公网。
- 发现同时听 IPv4 `239.255.255.250:1900` 和 IPv6 `ff02::c` / `ff05::c`。访客网络、AP 隔离和部分 Mesh 仍会让组播过不去。iOS 收不到组播 NOTIFY 时，要靠我们从 1900 端口单播回它的源端口。
- 进度主要靠控制点轮询 `GetPositionInfo`。播放器大约每 250 毫秒记下当前位置，所以手机读到的进度误差在这个范围内。`GetTransportInfo` 的 `CurrentSpeed` 跟电视上选的倍速一致；手机发来的 `Play` 仍然只接受速度 1，这样 Bilibili 的原有流程不变，暂停后继续也不会把倍速打回 1。音量约每秒从系统读一次，电视遥控器改过的音量会回到 `GetVolume`。地址返回 HTTP 401、403 或 410 时，画面上写「播放地址已失效，请在手机上重新投屏」。
- GENA 通知的是状态变化，不是每一帧进度。
- 播放界面的这些交互参考了 [puppy-store-cast](https://github.com/leiz56638111-create/puppy-store-cast) 公开 README 里的说明。那个仓库是 **GPL-3.0-only**，不是 MIT。这里没有复制它的源码，见 `THIRD_PARTY_NOTICES.md`。
- 外挂字幕、播放列表和下一首只做了最基本的保存，不会自动连播。
- 雷鸟 Android 11 上，第一笔 HTTP 请求会调用仅 API 33 才有的 `ByteArrayOutputStream.toString(Charset)`，进程因此退出。请求行已改为 `String(bytes, charset)`，HTTP 工作线程按连接捕获 `Throwable`。
- 空的 `CurrentURIMetaData`、`NOT_IMPLEMENTED`、未转义的 DIDL，以及 `Expect: 100-continue` / chunked 请求都按可选元数据处理。发现应答覆盖 `ssdp:all`，并在每块 IPv4 网卡上以 TTL 2 发送 3 次 NOTIFY。IPv6 链路组播同样宣告。M-SEARCH 的单播回复从绑定 1900 的套接字发回发送方的 ip:port，随机延迟不超过 800ms。设备描述带 `presentationURL`、PNG 图标、`dlna:X_DLNADOC`（DMR-1.50）和 `dlna:X_DLNACAP`。`SERVER` 使用逗号分隔，和 Platinum / redsonic 一样。出厂名仍是「投屏点我」，和电视自带的 redsonic / hpplay 分开。
- 每次 M-SEARCH（来源 ip:port、ST）和每条回复都会打在 logcat 标签 `ClickToCast` 下。
- 雷鸟鹏 6 SE（Android 11）上，versionCode 2 已经能被 Bilibili 通过 SSDP 发现，并且 `SetAVTransportURI` / `Play` / `Pause` / `Seek` 返回 HTTP 200。AirPlay 宣告、控制中心列表、设置页光晕和投屏记录重播还要在装上更新的包之后再看。4K/HDR 和长时间播放仍未在这台电视上验收。
- 投屏记录里，带 http(s) 地址的新记录可以用遥控器选中后按 OK 重播，请求头只用当时存下来的那些。同一地址再播（包括从记录里重播）会更新原来那一行的时间并把它挪到最上面，不会再插一条。更早的记录没有地址，行内会写「不能重播，没有保存播放地址」。

## 协议说明

设备描述里的 `friendlyName` 默认是「投屏点我」。SSDP 的 `LOCATION` 指向 `http://<电视IP>:8192/dmr/description.xml`，手机读到的名字来自这里。

元数据里可以用下面的片段附带请求头（发送端需要自己写进 `CurrentURIMetaData`）：

```xml
<userAgent>Mozilla/5.0</userAgent>
<header name="Referer">https://www.example.com</header>
```

## 许可证

本项目自带 UxPlay / RPiPlay 的 playfair 源码，因此整体采用 [GNU GPL-3.0-only](LICENSE)。版权人已把原先的 Apache-2.0 代码改授为 GPL-3.0-only，以便合法包含这些文件。

AndroidX Media3 与 BouncyCastle 仍按它们各自的 Apache-2.0 许可使用，可以和 GPL-3.0 一起分发。详见 `THIRD_PARTY_NOTICES.md`。
