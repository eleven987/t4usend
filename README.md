# T4U Send

把拓麻歌子 4U / 4U+ 的物品文件（`.jpg` 或裸 `.bin`）通过 NFC 推送给实机的安卓小工具。
An Android utility that pushes Tamagotchi 4U / 4U+ item files (`.jpg` or raw `.bin`) to a real device over NFC.

当前版本 1.0 / Version 1.0

- 不联网、不收集数据、无第三方 SDK
- 任意 CPU 架构均可安装（APK 内无原生库）
- 中英双语界面

---

## 中文

### 这是什么

拓麻歌子 4U / 4U+ 的物品（游戏、零食、装饰、出行……）通过手机 NFC 下发。官方做法是用手机贴住机器、分两步把物品推进去。本工具就是这个过程的开源实现。

物品文件在磁盘上的结构是：

```
[JPEG 图像 ... FF D9] + [紧贴其后的 TAMAGO 记录]
```

真正要推进机器的是后面那条记录，JPEG 只是外壳。本工具会自己剥掉外壳，只发记录。

### 三个功能

| 模式 | 说明 |
|---|---|
| 发送物品 | 选一个物品文件，分两次贴卡发给 4U。第 1 贴发 258 字节握手，4U 会回推一条 NDEF（里面是机器 ID 与生日）；第 2 贴发物品载荷。 |
| 碰触点写卡 | 往一张 NFC 卡里写 48 字节「碰触点」记录，写好后贴到 4U 上可触发游戏内事件。 |
| 读卡诊断 | 只读，不改卡。打印卡片的 tech 列表、UID、ATQA/SAK、第 0..15 页内容和芯片型号，用来验证读卡链路、判断卡型。 |

### 运行要求

| 项目 | 要求 |
|---|---|
| 系统版本 | Android 4.4 ~ 13（API 19 ~ 33） |
| CPU 架构 | 任意（armeabi-v7a / arm64-v8a / x86 / x86_64 都能装） |
| 硬件 | 支持 NFC 的手机 |
| 权限 | 仅 `android.permission.NFC`，不申请 INTERNET |

Android 14 及以上无法使用「发送物品」：系统的 NFC Beam 推送接口在该版本被移除。碰触点写卡与读卡诊断走的是另外的接口，不受影响。

### 使用方法

**发送物品**

机器侧（4U / 4U+，日文界面）：

1. 回到主画面。
2. 选「通信」—— 主画面图标栏里的**第二个**图标。
3. 进入后选「4Uアプリ」（4U App）—— 这个列表里的**第三个**。
4. 屏幕出现「タッチ！」提示，机器进入等待，这时它在等手机。

手机侧：

5. 点「选择文件（jpg / bin）」，选一个 4U 物品文件。
6. 点「开始发送」。
7. 把手机背面贴到 4U 的 NFC 位置 —— 第 1 贴，发握手。等一两秒，手机屏幕会缩小成一块小卡片（这是安卓系统的 NFC 发送界面），**点一下缩小的屏幕**，握手才会真正发出去。
8. **保持接触不要分开**，第 2 贴紧随其后发出 —— 屏幕会再缩小一次，**同样点一下**，物品进入机器，机器上会出现新物品。

机器实际会连两次（第一次验证、第二次传数据），这正是「两贴」的由来，所以全程都要保持贴合，只贴一次往往不够。

NFC 感应区在手机背面，一般在摄像头附近，具体位置随机型不同。老机器（Nexus 4 / 6 一类）感应区偏小、容易断，贴失败直接重贴即可；也可以点「重贴一次」退回第 1 步，或点「跳过握手，直接发物品」只贴一次。

**碰触点写卡**

1. 输入事件类型（1 ~ 10）。
2. 点「开始写卡」。
3. 贴一张 NFC 卡到手机背面。

写好之后，照着官方 TOUCH SPOT 的用法，停在主画面把这张卡贴到 4U 的 NFC 位置，即可触发游戏内事件。（本功能尚未在实体卡上验证，见下方「已知限制」。）

卡必须是 **Type 2** 的：NTAG213 / NTAG215 / NTAG216 或 Mifare Ultralight。Mifare Classic（M1，国内常见的「白卡」）不行 —— 它走 ISO14443-3A 但写命令不是 `0xA2`，会写入失败。拿不准卡型就先点「读卡诊断」贴一下看结果。

### 隐私

- 只申请 `android.permission.NFC` 一个权限。
- 不申请 `INTERNET`，代码里没有任何网络调用，不会发送任何数据。
- 无第三方 SDK、无统计、无广告、无崩溃上报。
- 读取物品文件用的是系统的文件选择器，只拿到你主动选择的那一个文件。
- 全程离线运行。

### 已知限制

- **「碰触点写卡」与「读卡诊断」尚未在实体卡上验证过（开发时手边没有可用的 Type 2 卡）。**
- 发送速度受 4U 自身的 NFC 与闪存写入速度限制，比较慢。

### 免责声明

本项目是非官方的个人研究项目，与 BANDAI、WiZ 及任何官方机构无关。
本仓库不包含任何官方物品文件、游戏数据或固件镜像。
请自行准备合法来源的物品文件，并自行承担修改设备内容的风险。

### 许可证

MIT 许可证，见 [LICENSE](LICENSE)。
Copyright (c) 2026 T4U Send contributors

---

## English

### What it is

Tamagotchi 4U / 4U+ content (games, snacks, decorations, outings, ...) is delivered over the phone's NFC. The official app works by holding the phone against the device and pushing the item in two taps. This tool is an open implementation of that process.

On disk an item file looks like this:

```
[JPEG image ... FF D9] + [TAMAGO record immediately after]
```

The record is what actually goes into the device; the JPEG is only a wrapper. This app strips the wrapper itself and sends just the record.

### Three features

| Mode | Description |
|---|---|
| Send item | Pick an item file and tap the device twice. The first tap sends a 258-byte handshake, and the 4U pushes an NDEF message back (it carries the device ID and birthday). The second tap sends the item payload. |
| Write touch spot | Write a 48-byte "touch spot" record onto an NFC card. Tapping that card to the 4U later triggers an in-game event. |
| Card diagnostics | Read-only. Prints the card's tech list, UID, ATQA/SAK, pages 0..15 and chip model. Use it to verify the read path and to identify the card type. |

### Requirements

| Item | Requirement |
|---|---|
| Android version | 4.4 through 13 (API 19 - 33) |
| CPU architecture | Any (armeabi-v7a / arm64-v8a / x86 / x86_64) |
| Hardware | A phone with NFC |
| Permissions | `android.permission.NFC` only; no INTERNET |

"Send item" does not work on Android 14 or newer, because the NFC Beam push API was removed in that version. "Write touch spot" and "Card diagnostics" use a different API and are unaffected.

### Usage

**Send item**

On the device (4U / 4U+, Japanese UI):

1. Go back to the home screen.
2. Select "Connection" - the **second** icon in the home screen's icon bar.
3. In that menu, select "4Uアプリ" (4U App) - the **third** entry in the list.
4. The screen shows the "タッチ！" prompt and the device starts waiting for the phone.

On the phone:

5. Tap "Pick file (jpg / bin)" and choose a 4U item file.
6. Tap "Start sending".
7. Hold the back of the phone against the 4U's NFC area - first tap, handshake. After a second or two the phone screen shrinks into a small card (that is Android's NFC send UI); **tap the shrunk screen** and the handshake is actually sent.
8. **Keep the two in contact and do not pull them apart** - the second tap follows right after; the screen shrinks again, **tap it the same way**, and the item goes in. It shows up on the device.

The device actually connects twice (once to verify, once to download) - that is where the two-tap model comes from, so keep them in contact the whole time; a single tap is usually not enough.

The NFC antenna sits on the back of the phone, usually near the camera; the exact spot varies by model. Older phones (Nexus 4 / 6 and similar) have a small, flaky antenna - if a tap fails, just tap again. You can also tap "Back to tap 1" to return to step 1, or "Skip handshake, send item" to skip the handshake and tap only once.

**Write touch spot**

1. Enter an event type (1 - 10).
2. Tap "Write card".
3. Hold an NFC card to the back of the phone.

Once written, use the card the same way as an official TOUCH SPOT: stay on the home screen and hold the card against the 4U's NFC area to trigger the in-game event. (Not verified on a physical card yet - see "Known limitations" below.)

The card must be **Type 2**: NTAG213 / NTAG215 / NTAG216 or Mifare Ultralight. Mifare Classic (M1, the common "white card") will not work - it speaks ISO14443-3A but its write command is not `0xA2`, so the write will fail. If unsure, use "Card diagnostics" first.

### Privacy

- Requests exactly one permission: `android.permission.NFC`.
- No `INTERNET` permission and no network calls anywhere in the code. Nothing is transmitted.
- No third-party SDKs, no analytics, no ads, no crash reporting.
- Item files are opened through the system file picker, so the app only ever sees the single file you choose.
- Everything runs offline.

### Known limitations

- **"Write touch spot" and "Card diagnostics" have not been verified on a physical card yet (no usable Type 2 card was available during development).**
- Transfer speed is limited by the 4U's own NFC and flash write speed, and is fairly slow.

### Disclaimer

This is an unofficial personal research project, not affiliated with BANDAI, WiZ or any official entity.
This repository contains no official item files, game data or firmware images.
Bring your own legally obtained item files, and accept the risk of modifying your device.

### License

MIT License. See [LICENSE](LICENSE).
Copyright (c) 2026 T4U Send contributors
