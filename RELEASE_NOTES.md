<!--
  The body of a GitHub Release, in Chinese and then in English.

  The token in the artifact tables below is replaced with the tag the release is built from,
  so the file names follow the tag without anyone editing them per release. Everything else
  is written by hand for the release at hand: what changed, and what a user has to do about
  it. Both platforms' jobs read this file, so the two jobs cannot publish different notes.
-->
> **本版本新增解锁密码与生物识别解锁。** 主密钥不再只躺在平台密钥库里：它被 Argon2id 派生的密码包裹起来（`master.key.pw`），设备支持时再加一层由系统认证放行的包裹（`master.key.bio`，Windows Hello 或 Android 生物识别）。打开应用凭密码或一次系统认证，密码始终是回来的路——平台凭据被删掉、换了指纹或人脸，仍能用密码进去。锁定可以随时在设置里点，15 分钟没有操作也会自动锁定；应用不在后台锁定，切到别的窗口不会立刻要求解锁。启动时直接弹出系统认证，不需要先点按钮。
>
> 同时修复真机复验发现的问题：Android 上点「在局域网里查找设备」立即崩溃（权限请求码超出平台允许的范围）；局域网在 Android 上收不到任何一台设备（组播 socket 绑在了单播地址上，而组播报文的目的地址是组地址）；设备重开一次查找后会被对端一直显示为「已离线」（宣告序号的生命周期跨过了会话）；设置页里生物识别的结果提示落在整页最底部，看上去像没反应。

Qualia2FA，桌面与 Android 应用。

**请把所有设备一起升级。** 保险库文件格式仍是 4，0.4.0 能直接打开 0.3.x 写出的保险库，打开后要求在这台设备上设置解锁密码。反过来不行：一旦某台设备启用了锁定，它的主密钥就不在平台密钥库里单独存在了，0.3.x 读不出它。同步载荷仍是 4，0.4.0 与 0.3.x 之间仍可同步。

| 平台                      | 产物                                                 | 说明                                                                                                    |
|---------------------------|------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Android 8.0+（64 位 ARM） | `Qualia2FA-@VERSION@-arm64.apk`                      | 用项目的发布密钥签名                                                                                    |
| Windows 10/11（x64）      | `Qualia2FA-@VERSION@.msi`、`Qualia2FA-@VERSION@.exe` | 已签名，但证书尚不被 Windows 信任：安装器会警告；在证书来自受信任的签发者之前，这个签名不能当作身份依据 |

Windows 上的生物识别解锁由 Windows Hello 提供，需要系统支持 WebAuthn 的 PRF 扩展（较新的 Windows 11 构建）。系统不支持时这一项如实报告不可用，密码照常可用。

应用不在后台同步。配对与同步的局域网部分只在需要它的界面打开时启用；首次配对仍然要读二维码，它把两台设备绑定在一起。

---

> **This version adds the unlock password and biometric unlock.** The master key no
> longer sits in the platform key store on its own: it is wrapped by a key derived from
> a password with Argon2id (`master.key.pw`), and, where the device can do it, by a second
> wrap that only a platform authentication releases (`master.key.bio`: Windows Hello or the
> Android biometric prompt). Opening the application costs the password or one system
> gesture, and the password always stays the way back in - a deleted credential, a new
> finger or a new face still leaves it usable. Locking is there whenever the settings are
> asked for it, and fifteen minutes without a touch locks the vault as well; the
> application does not lock because another window came to the front. A launch goes
> straight to the system gesture instead of waiting for the button to be found.
> 
> Alongside it, four defects found on a device: on Android, "find devices on the local
> network" crashed at once (the permission request code was outside the range the platform
> accepts); the local network heard no device at all on Android (the multicast socket was
> bound to a unicast address, while a multicast datagram names the group); a device that
> reopened its search stayed "offline" on the other side (the announcement counter's
> lifetime outlived the session); and the biometric result was written at the very bottom of
> a page taller than the screen, which read as no reaction at all.

Qualia2FA, the desktop and Android application.

**Upgrade every device together.** The vault file format is still 4, and 0.4.0 opens a
vault written by 0.3.x directly, asking for an unlock password for this device afterwards.
The other direction does not hold: once a device has the lock enabled, its master key no
longer exists in the platform key store alone, so 0.3.x cannot read it. The sync payload
is still 4, and 0.4.0 still synchronizes with 0.3.x.

| Platform                  | Artifact                                             | Notes                                                                                                                                                                             |
|---------------------------|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Android 8.0+ (64-bit ARM) | `Qualia2FA-@VERSION@-arm64.apk`                      | signed with the project's release key                                                                                                                                             |
| Windows 10/11 (x64)       | `Qualia2FA-@VERSION@.msi`, `Qualia2FA-@VERSION@.exe` | signed, with a certificate Windows does not trust yet: the installer will warn, and the signature is not an identity to rely on until the certificate comes from a trusted issuer |

Biometric unlock on Windows is Windows Hello, and it needs a system that supports the
PRF extension of WebAuthn (recent Windows 11 builds). Where the system does not, the
option reports itself as unavailable and the password keeps working.

Nothing is synchronized in the background. The local-network half of pairing and
syncing is opened while a screen that needs it is up, and the first pairing still
reads a QR code, which is what pins the two devices to each other.
