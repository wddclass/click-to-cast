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

如果以后要搬它的源码，必须先按 GPL-3.0 处理，不能只在这里补一行署名。

## pyatv

- 仓库：https://github.com/postlund/pyatv
- 许可证：MIT

AirPlay 的旧版 pair-verify 报文布局（`01 00 00 00` 开头的公钥、SHA-512 标签 `Pair-Verify-AES-Key` / `Pair-Verify-AES-IV`、第二包签名从第 64 字节密钥流开始）对照的是 pyatv 里公开的客户端流程。Kotlin 实现是自己写的，没有拷贝它的 Python。

## FairPlay `fp-setup` 应答表

`POST /fp-setup` 的四份 142 字节应答和 12 字节第二阶段头，是各家 AirPlay 接收端里同一组固定字节，用来让 iPhone 继续发 `SETUP`。本仓库用 Kotlin 按长度和 mode 字节重新实现了选择逻辑，没有复制 UxPlay、RPiPlay 或 shairport-sync 的 C 源码。

这些字节来自对 Apple FairPlay 的逆向，不是本项目原创。MIT 许可盖不住这组 Apple 派生数据，DMCA 对技术措施的限制也跟许可证不是一回事。这里只为了让用户自己的 iPhone 连上自己的电视。这不是法律意见。

UxPlay / RPiPlay 里解开会话密钥的 `playfair_decrypt`（`playfair/omg_hax.c`）是 GPL。把它抄进来会让整个应用没法再按 Apache-2.0 发布，必须改成 GPL-3.0。因此这一版没有做密钥解开，也没有解密镜像的 H.264。
