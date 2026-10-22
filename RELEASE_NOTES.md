<!--
  The body of a GitHub Release, in Chinese and then in English.

  The token in the artifact tables below is replaced with the tag the release is built from,
  so the file names follow the tag without anyone editing them per release. Everything else
  is written by hand for the release at hand: what changed, and what a user has to do about
  it. Both platforms' jobs read this file, so the two jobs cannot publish different notes.
-->
> **本版本是 0.4.0 的修复版。** 它处理真机复验发现的问题：Android 上导入保险库不再崩溃；Windows 的文件选择框在文件类型里显示文件后缀；设置界面可以直接导入保险库，导入后要求为这台设备设置新的解锁密码；点击输入框以外的区域会收起键盘与光标；设置密码与修改密码的「再次输入」字段名称正确；导出默认选中「加密保险库」。
>
> 保险库文件格式仍是 4，同步载荷仍是 4：0.4.0 与 0.4.1 可以互相打开、互相同步，升级不需要迁移数据。

Qualia2FA，桌面与 Android 应用。

**请把所有设备一起升级。** 保险库文件格式仍是 4，0.4.1 能直接打开 0.3.x 与 0.4.0 写出的保险库；一旦某台设备启用了锁定，它的主密钥就不在平台密钥库里单独存在了，0.3.x 读不出它。同步载荷仍是 4，0.4.1 与 0.3.x、0.4.0 之间仍可同步。

| 平台                      | 产物                                                 | 说明                                      |
|---------------------------|------------------------------------------------------|-------------------------------------------|
| Android 8.0+（64 位 ARM） | `Qualia2FA-@VERSION@-arm64.apk`                      | 用项目的发布密钥签名                      |
| Windows 10/11（x64）      | `Qualia2FA-@VERSION@.msi`、`Qualia2FA-@VERSION@.exe` | 未签名，首次运行时会触发 SmartScreen 警告 |

Windows 上的生物识别解锁由 Windows Hello 提供，需要系统支持 WebAuthn 的 PRF 扩展（较新的 Windows 11 构建）。系统不支持时这一项如实报告不可用，密码照常可用。

应用不在后台同步。配对与同步的局域网部分只在需要它的界面打开时启用；首次配对仍然要读二维码，它把两台设备绑定在一起。

---

> **This release fixes defects in 0.4.0.** It addresses what a pass on real devices
> found: importing a vault no longer crashes on Android; the desktop file dialog shows
> the file suffixes in its file-type entry; a vault can be imported from the settings,
> which asks for a new unlock password for this device afterwards; a tap outside a
> field dismisses the keyboard and the caret; the repeat fields of both password
> screens carry the right names; and the export screen starts on the encrypted vault.
>
> The vault file format is still 4 and so is the sync payload: 0.4.0 and 0.4.1 open
> each other's vaults and still synchronize, with no data migration.

Qualia2FA, the desktop and Android application.

**Upgrade every device together.** The vault file format is still 4, and 0.4.1 opens a
vault written by 0.3.x or 0.4.0 directly. The one direction that does not hold is
0.3.x reading a locked device's vault: once the lock is enabled, the master key no
longer exists in the platform key store alone. The sync payload is still 4, and 0.4.1
still synchronizes with 0.3.x and 0.4.0.

| Platform                  | Artifact                                             | Notes                                              |
|---------------------------|------------------------------------------------------|----------------------------------------------------|
| Android 8.0+ (64-bit ARM) | `Qualia2FA-@VERSION@-arm64.apk`                      | signed with the project's release key              |
| Windows 10/11 (x64)       | `Qualia2FA-@VERSION@.msi`, `Qualia2FA-@VERSION@.exe` | not code-signed, so SmartScreen warns on first run |

Biometric unlock on Windows is Windows Hello, and it needs a system that supports the
PRF extension of WebAuthn (recent Windows 11 builds). Where the system does not, the
option reports itself as unavailable and the password keeps working.

Nothing is synchronized in the background. The local-network half of pairing and
syncing is opened while a screen that needs it is up, and the first pairing still
reads a QR code, which is what pins the two devices to each other.
