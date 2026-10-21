# 投屏点我

安卓电视上的轻量 DLNA 接收端。手机和电视在同一个局域网里时，视频 App 的投屏列表里会看到一台叫 **投屏点我** 的设备。选中之后，电视用 AndroidX Media3 播放对方发来的地址。

没有账号、广告或统计。应用不提供片源，只接收发送端给出的播放地址。

出厂设备名就是「投屏点我」。设置里可以改成别的名字，但不会自带「客厅」「卧室」这类前缀，避免手机列表里出现含糊的名字。

## 能做什么

- SSDP 发现，以及 UPnP MediaRenderer（DMR）描述。
- AVTransport：`SetAVTransportURI`、`Play`、`Pause`、`Stop`、`Seek`、`GetPositionInfo`、`GetTransportInfo`、`GetMediaInfo`，以及能力、播放模式和当前可用动作查询。
- RenderingControl：主声道音量和静音。
- ConnectionManager：`GetProtocolInfo`、当前连接查询。
- GENA 事件：状态变化时向已订阅的控制点发送 `LastChange`。
- 播放 HTTP(S) 渐进式 MP4、HLS、DASH，以及 ExoPlayer 能识别的 FLV。地址被标错时，会先读一小段内容再判断是不是 HLS / DASH / FLV。
- 可从 DIDL 元数据里带上 `User-Agent`、`Referer` 等请求头。
- 前台服务常驻，可开机自启，回到桌面后仍然可被发现。
- 遥控器：左右快进 10 秒，上下调节音量，OK 暂停，返回结束本次投屏。

4K 和 HDR 走电视自己的硬解码。支持与否取决于固件和片源是否加密，应用不会强行软解。

## 在 TCL 雷鸟上安装

目标机型参考：雷鸟鹏 6 SE（Amlogic T982，Android 11，界面 1920×1080）。安装包是通用 APK：没有只针对 64 位的原生库，32 位 `armeabi-v7a` 和 64 位系统都能装。

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

- 只实现标准 DLNA / UPnP。AirPlay、Miracast、Chromecast、乐播 / 乐联私有协议都不在这个版本里。
- 爱奇艺、优酷、腾讯视频、Bilibili 的部分版本只走私有协议，或者地址带短时签名和 DRM。它们走标准 DLNA 时可以被发现；走私有协议时列表里不会出现本应用，或者给出地址后无法播放。
- 不转码。编码、封装或 DRM 电视硬解不了，就会直接失败。
- 控制面没有配对。请只在自己的家庭网络里使用，不要把 8192 端口映射到公网。
- 只保证 IPv4。访客网络、AP 隔离和部分 Mesh 会让 SSDP 组播过不去。
- 进度主要靠控制点轮询 `GetPositionInfo`。GENA 通知的是状态变化，不是每一帧进度。
- 外挂字幕、播放列表和下一首只做了最基本的保存，不会自动连播。
- 雷鸟 Android 11 上，第一笔 HTTP 请求会调用仅 API 33 才有的 `ByteArrayOutputStream.toString(Charset)`，进程因此退出。请求行已改为 `String(bytes, charset)`，HTTP 工作线程按连接捕获 `Throwable`。
- 空的 `CurrentURIMetaData`、`NOT_IMPLEMENTED`、未转义的 DIDL，以及 `Expect: 100-continue` / chunked 请求都按可选元数据处理。发现应答覆盖 `ssdp:all`，并在每块 IPv4 网卡上以 TTL 2 发送 3 次 NOTIFY。设备描述带 `presentationURL` 和 PNG 图标。出厂名仍是「投屏点我」，和电视自带的 redsonic / hpplay 分开。
- 发现、起播、暂停、seek、4K/HDR 和长时间播放仍未在雷鸟鹏 6 SE 上验收。

## 协议说明

设备描述里的 `friendlyName` 默认是「投屏点我」。SSDP 的 `LOCATION` 指向 `http://<电视IP>:8192/dmr/description.xml`，手机读到的名字来自这里。

元数据里可以用下面的片段附带请求头（发送端需要自己写进 `CurrentURIMetaData`）：

```xml
<userAgent>Mozilla/5.0</userAgent>
<header name="Referer">https://www.example.com</header>
```

## 许可证

原创代码采用 [Apache License 2.0](LICENSE)。

界面和协议实现都是按 DLNA / UPnP 规范另写的，没有使用 GPL 代码。AndroidX Media3 沿用其自身的 Apache-2.0 许可。
