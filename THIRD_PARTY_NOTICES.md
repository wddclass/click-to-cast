# 第三方说明

## puppy-store-cast

- 仓库：https://github.com/leiz56638111-create/puppy-store-cast
- 许可证：GNU GPL-3.0-only（仓库 LICENSE 与 README 都写明是 GPL-3.0-only，不是 MIT）

本项目没有复制该仓库的源代码，也没有把它的源码改写后放进来。播放界面上的这些行为，只参考了它公开 README 里对交互的描述，并用自己的 Kotlin 重新实现：

- 遥控器可以聚焦的进度条，左右大约 10 秒
- 倍速菜单
- 控制条显示和隐藏
- 平板触摸
- 后台收到投屏时，引导用户允许「显示在其他应用上层」
- 新的投屏换掉正在播的内容
- 把进度、传输状态和音量报回手机
- 暂停后从原位置、原倍速继续
- 播放地址过期时的中文提示

本仓库现在整体是 GPL-3.0-only。仍然没有复制 puppy-store-cast 的源码。

## pyatv

- 仓库：https://github.com/postlund/pyatv
- 许可证：MIT

AirPlay 的旧版 pair-verify 报文布局（`01 00 00 00` 开头的公钥、SHA-512 标签 `Pair-Verify-AES-Key` / `Pair-Verify-AES-IV`、第二包签名从第 64 字节密钥流开始）对照的是 pyatv 里公开的客户端流程。Kotlin 实现是自己写的，没有拷贝它的 Python。

## playfair（UxPlay / RPiPlay）

屏幕镜像解开 FairPlay `ekey` 用的是 UxPlay 里的 playfair，源码在 `app/src/main/cpp/playfair/`：

- `playfair.c` / `playfair.h`
- `omg_hax.c` / `omg_hax.h`
- `modified_md5.c`
- `sap_hash.c`
- `hand_garble.c`
- 同目录的 `LICENSE.md`（GNU GPL-3.0）

来源与致谢：

- [UxPlay](https://github.com/FDH2/UxPlay)（fduncanh），GPL-3.0。镜像 AES 密钥的做法与它对齐：`playfair_decrypt` 得到 16 字节后，若有 pair-verify 的 32 字节 X25519 共享密钥，则取 SHA-512(`aeskey` ‖ `ecdh_secret`) 的前 16 字节；视频密钥再取 SHA-512(`AirPlayStreamKey` + 十进制 `streamConnectionID` ‖ aeskey) 和 SHA-512(`AirPlayStreamIV` + 十进制 id ‖ aeskey) 的前 16 字节。报文布局（128 字节头、type 0 视频、type 1 的 SPS/PPS、长度前缀改成 Annex-B）也按 UxPlay 的 `raop_rtp_mirror.c` / `mirror_buffer.c` 用 Kotlin 重写，没有把那些文件整份拷进来。
- [RPiPlay](https://github.com/FD-/RPiPlay)（Juho Vähä-Herttua 等），playfair 的上游。UxPlay 的 `lib/playfair` 延续的就是这套实现。
- playfair 目录里的算法（`omg_hax`、modified MD5、`sap_hash`、`hand_garble`）是 FairPlay 互操作代码，不是本项目原创。JNI 只是把 `playfair_decrypt(message3, ekey, keyOut)` 接到 Kotlin。

因为这些文件是 GPL-3.0，整个「投屏点我」改为 GPL-3.0-only。分发这个应用时必须同时提供对应源代码，并保留上述版权与许可声明。这不是法律意见。
