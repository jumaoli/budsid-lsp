# 小米耳机代号替换（BudsId）

[English](README.en.md) · 中文

![build](https://github.com/jumaoli/budsid-lsp/actions/workflows/build.yml/badge.svg)
![release](https://img.shields.io/github/v/release/jumaoli/budsid-lsp)

一个 LSPosed 模块：把小米蓝牙识别到的耳机代号（deviceId）换成另一款小米耳机，
从而直接复用目标型号的**连接弹窗动画**、**超级岛视频**、**设置页设备图**和**功能页条目**。
只改显示身份，不动 A2DP/HFP 音频链路。

- 模块包名：`io.github.jumaoli.budsid`
- 作用域：`com.xiaomi.bluetooth`（弹窗、超级岛、设备详情都由它提供）
- 适配环境：HyperOS 4 / Android 17（实测机型 M610BB），理论上 HyperOS 2/3 同样适用

---

## 1. 原理

小米蓝牙的「耳机代号」来自两处：

| 来源 | 代码位置 |
| --- | --- |
| 广播解析 | `com.android.bluetooth.ble.app.fastconnect.MiuiAdvDataInfo#getDeviceId()`<br>`MiuiAdvDataInfoV2#getDeviceId()`<br>`MiuiHeadsetInfo#getDeviceId()`<br>`MiuiHeadsetInfoV2#getDeviceId()` |
| 缓存 | `DeviceIdCached` SharedPreferences 里的 `saved<MAC>`，经 `BluetoothReflect#getDeviceId(String, Context)` 读取 |

代号本身由广播字节拼出来：V2 是 `String.format("%06X%04X", majorId, minorId)`，
major/minor 取自 `mAdvData[1..4]`；V1 取 `mAdvData` 的对应位。

拿到代号后，整套资源都按它取：

```
弹窗逐帧        /data/user_de/0/com.xiaomi.bluetooth/files/fc_resources/<deviceId>/fc_<deviceId>_<frame>.webp
资源描述        .../fc_resources/<deviceId>/resource_record.xml
超级岛 mp4      .../files/my_internal_files/target/<deviceId>/*
CDN 弹窗包      https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect/<deviceId>.zip
CDN 超级岛包    https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect_video_new/<deviceId>.zip
```

模块做的事就是在这几个 `getDeviceId()` 返回处替换字符串，并且把 `saved<MAC>` 的读取一并替换。
下游的 `DeviceClassify`、`MiuiCloudParser`、`MiuiBluetoothNotification` 就会自然按新代号
下载并展示资源，不需要再逐个 hook 资源路径。

另外对 `MiuiBluetoothNotification#getResourceUri(name, deviceId)` 做了兜底替换，
防止某些超级岛分支拿到旧代号。

插件 APK 由 Qigsaw 用独立 ClassLoader 加载，所以模块同时 hook 了
`ClassLoader#loadClass`，在 `Miui*` 类被加载的瞬间补上 hook。

弹窗里的产品图不走 `getDeviceId()`，而是直接按广播原始代号读
`fc_resources/<deviceId>/fc_<deviceId>_*.webp`。模块在 `MiuiFastConnectResource#getDrawableFromFile`
里做一次资源移植：把目标型号的整套图片按原名复制进当前代号目录，弹窗、盒子图、逐帧动画就都换过来了。

MiLink（融合设备中心）通过 `MxBluetoothManager/MxBluetoothService#getDeviceId(BluetoothDevice)`
读代号，模块也一并 hook，作用域需要加上 `com.milink.service`。

## 2. 使用

1. 安装 APK，在 LSPosed 里启用，作用域勾选 `com.xiaomi.bluetooth`。
2. 打开「耳机代号替换」：
   - **已配对耳机**里点你的耳机 → 选一个目标型号；
   - 或「添加规则」手填：真实代号 → 目标代号（`*` 表示全部设备，可加 MAC 限定）。
3. 强制停止「小米蓝牙」，或断开重连耳机。首次会按新型号下载弹窗/超级岛资源。

> 长按代号库里的任意型号，可以复制它的弹窗动画 / 超级岛视频 CDN 链接。

## 3. 实测记录（M610BB / HyperOS 4 / Android 17）

目标：`020103004D`（Xiaomi Buds 5 Pro Wi-Fi）→ `01013E00`（Xiaomi Air 5）

```
BudsId: hooked ...MiuiAdvDataInfo.getDeviceId x1
BudsId: hooked ...MiuiAdvDataInfoV2.getDeviceId x1
BudsId: hooked ...MiuiHeadsetInfo.getDeviceId x1
BudsId: hooked ...MiuiHeadsetInfoV2.getDeviceId x1
BudsId: hooked ...BluetoothReflect.getDeviceId(String,Context)
BudsId: hooked ...MiuiBluetoothNotification.getResourceUri
BudsId: hooked ...miuibluetoothprovider.MiuiBluetoothContentProvider.query x1
BudsId: hooked com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager.getDeviceId(BluetoothDevice)
BudsId: hooked com.xiaomi.mxbluetoothsdk.service.MxBluetoothService.getDeviceId(BluetoothDevice)
BudsId: #1 020103004D -> 01013E00 @AA:BB:CC:DD:EE:FF
MiuiControllerCommonV2_Plugin: provider info = 111111101111000110100000,01013E00
BudsId: image sync 020103004D <- 01013E00 copied=285
```

结果：

- 弹窗 Activity `com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity` 正常启动；
- 弹窗里的**产品图已经变成 Air 5 的白色耳机+充电盒**（实测截图见 `dist/screenshot-popup-air5.png`）；
- `fc_resources/01013E00/` 自动生成 **286 帧** webp；`my_internal_files/target/01013E00/` 自动生成；
- 设备数据库 `…/deviceinfo` 查询返回新代号；
- 作用域里的 MiLink（`com.milink.service`）也注入了 `MxBluetooth*#getDeviceId`。

### 弹窗图片是怎么换的

弹窗取图实际走的是：

```
MiuiFastConnectResource.getDrawableFromFile(deviceId, "fc_<deviceId>_hs_ok.webp")
  → files/fc_resources/<deviceId>/<file>
```

这个 `deviceId` 是广播里的原始值（`020103004D`），不是 `getDeviceId()` 的返回值，所以光 hook 代号不够。
模块在 `getDrawableFromFile` 里把**读文件的代号换成目标代号**，并且先检查目标文件是否存在：

- `fc_<原代号>_hs_ok.webp` → `fc_<目标代号>_hs_ok.webp`（存在才换，`.webp`/`.png` 都会试）
- 逐帧动画（1 参数版本）会临时把 `mFolderName` 指向目标目录，读完立刻还原

这样不会修改系统文件，停用模块后弹窗自动回到原样。

## 4. 界面（对齐 SukiSU Ultra 的 Miuix 风格）

参考机主给的 SukiSU Ultra v4.2.1 管理器界面重做：

- 左上大标题（30sp 粗体）+ 副标题，内容直接铺在浅灰背景上
- 卡片式分组，行内「图标 + 标题 + 说明 + 右侧开关/箭头」，无分割线
- 底部四页悬浮胶囊导航：设备 / 规则 / 设置 / 关于，图标 + 文字，角标显示规则数与作用域数，选中项有浅蓝圆形底
- 设置页每行独立图标（模糊、悬浮、玻璃、角标、状态、缩放、明暗、手势）
- 界面设置：界面模糊、悬浮底栏、液态玻璃、导航栏角标、显示完整状态、界面缩放（85/100/115%）、明暗模式

### 模糊 / 液态玻璃（直接移植 SukiSU Ultra 的方案）

从 `com.sukisu.ultra` 里把 Backdrop 的 AGSL 着色器原样取出来用了：

- `LiquidGlassLensDispersion`（`uniform shader content / size / offset / cornerRadii /
  refractionHeight / refractionAmount / depthEffect / chromaticAberration`）整体内嵌进 `GlassBar`；
- 渲染链路：整屏抓一次 1/8 缩略图（约 42k 像素）→ 两栏共用同一张快照 →
  `RuntimeShader` 做边缘折射 + 七通道色散 → 半透明着色 + 镜面亮边 + 斜向反光 + 底部内阴影；
- 滚动停止 130ms 后才抓一次，滚动中保留上一帧；模糊在 GPU 上做，CPU 只负责一次缩略图绘制；
- 顶栏用高不透明度着色（隐约透出内容），底栏保留更明显的玻璃折射；
- 底部弹层用系统 `FLAG_BLUR_BEHIND` + `Window.setBackgroundBlurRadius(80dp)`。

> 设备是 Android 17，`RuntimeShader`（API 33+）直接可用；低版本会自动回退到纯模糊。
- 手势：预测性返回、横移返回、页面切换、跨轴拦截

### 导航栏选中气泡

- 选中项背后是一个 `GlassDot`：圆形裁切共享的模糊快照 + 镜面亮边 + 底部内阴影；
- 气泡随 `Backdrop` 一起刷新，切换 tab 时直接复用同一张快照，不额外抓图；
- 底栏着色降到 0x33，能看见后面内容被模糊/折射，和 SukiSU 录屏里的观感一致。

### 参数直接照 SukiSU 源码

看了 `ShirkNeko/SukiSU-Ultra` 源码（Apache-2.0，改编自 Kyant0/AndroidLiquidGlass）：

- `FloatingBottomBar.kt`：`containerColor = surfaceContainer.copy(0.4f)`，`blur(4dp, 4dp)`，`vibrancy()`，
  `lens(refractionHeight = 24.dp, refractionAmount = 24.dp)`；
- `liquid/Lens.kt`：uniform 里 `refractionAmount` 取的是 **负值**（`-scaledRefractionAmount`），
  所以是放大透镜而不是收缩；
- 选中指示器高光：BloomStroke 1dp，主光 (0.5, -0.3)，副光 (0.5, 0.8, 0.4)，白色 12%。

对应到本项目：折射高度 24dp、折射量 **-24dp**、depthEffect 0、色散 0、模糊半径 2（1/8 缩略图上）、
底栏着色 0x33、气泡透镜放大 1.11x + 饱和度 1.18 + 镜面亮边 + 内阴影。

### 跟手拖动

- 底栏是 `NavBar`（FrameLayout）：底层 `GlassBar` 负责磨砂，上面是 `GlassDot` 选中气泡，最上层是 4 个 tab；
- 手指按住横向拖动时气泡实时跟手（`onInterceptTouchEvent` 只拦截横向手势，竖滑不受影响）；
- 松手用 `OvershootInterpolator(0.9)` 320ms 回弹到最近 tab，并切换页面；
- 气泡大小 = tab 宽度 × 栏高，圆角取一半，所以看起来是胶囊/圆形玻璃透镜。

### 模糊改成 GPU 实时

- `Backdrop` 只做 1/4 缩放快照，不再在 CPU 上跑栈模糊；
- 每根栏用 `RenderNode` + `RenderEffect.createBlurEffect(4dp)` 做 **GPU 高斯模糊**，
  节点四周多留 2×blur 的 padding，避免边缘发黑；
- 快照按 90ms 节流刷新，滚动过程中也在更新，不是停手才糊；
- 快照再叠 `setSaturation(1.2)` 模拟 SukiSU 的 `vibrancy()`。

### 高光跟随重力 + 按压缩放

- `NavBar` 注册 `TYPE_GRAVITY`，按 3° 量化 `atan2(gy, gx)` 得到高光方向，传给 `GlassDot`；
- `GlassDot` 的镜面亮边是沿该方向的线性渐变（双峰：主光 0xF2、副光 0xB3），对应源码的
  `BloomStroke(primaryLight, secondaryLight)`；
- 按到底栏时气泡放大到 1.25x（源码 `pressedScale = 78/56 ≈ 1.39`），松手回弹；
- 高光方向随手机倾斜实时变化，`|g| < 0.1` 时回落到正上方。

### 弹窗图片为什么一开始是灰占位

- 插件类由 Qigsaw 在 Application 创建阶段才挂到 classloader，`handleLoadPackage` 时
  `findClass` 还找不到 `MiuiFastConnectResource`（日志里是 `fast image hook unavailable`）。
  现在改成每 250ms 重试、最多 10 秒，一定装得上；
- 目标型号第一次连接时，`fc_resources/<目标代号>/` 还没下载，系统只能画占位剪影。
  现在连接开始就后台把 CDN 的弹窗包下下来解压到**目标代号自己的目录**（只新建，不覆盖），
  日志 `prefetch <id> files=N`；下载完成后重连一次就是完整的产品图；
- 原设备目录（如 020103004D）不写、不替换，hash 与原包一致。

### 代号库实时更新

- 小米把**所有型号**放在 `https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect/00000000.zip`
  里的 `resource_record.xml`（455 条，含 deviceId / deviceName / disabled / tws 等字段）；
- 应用启动时后台拉一次（24 小时缓存），解析出有效型号合并进列表，存 `files/devices_cloud.json`；
- 设置页「代号库 → 更新小米代号库」可手动刷新，成功提示「已同步 N 个型号」；
- 只读取 CDN，不写系统目录。

### 按界面开关 hook

设置页新增「Hook 界面」卡片，三个开关分别控制：

| 开关 | 影响的 hook | 关掉后 |
| --- | --- | --- |
| 连接弹窗 | `MiuiAdvDataInfo/MiuiHeadsetInfo.getDeviceId`、`getDrawableFromFile` 的读路径替换、目标资源预下载 | 弹窗保持真实型号 |
| 超级岛 | `MiuiBluetoothNotification.getResourceUri` 里的代号映射 | 超级岛保持真实型号 |
| 详情页 | `MxBluetoothManager/MxBluetoothService.getDeviceId`、`deviceinfo` provider 的游标重写 | 设置/详情页保持真实型号 |

开关写进 `Prefs`，通过 `ConfigProvider` 下发给注入进程，`Mapper` 1.5 秒内生效，
无需重启蓝牙；已连接状态下的下一次取图/查询就会按新开关走。

### 型号缩略图

- 代号选择列表每行左侧 44dp 缩略图，来源为 `fast_connect/<id>.zip` 里的 `fc_<id>_hs_ok.webp`；
- 找不到 `_hs_ok` 时退而取 `_hs*`，再退 `_box`，没有就留空占位；
- 内存 LRU（48 张）+ 磁盘缓存 `files/thumbs/<id>.webp`，后台 2 线程下载，列表滚动不卡；
- 只读 CDN，不写系统目录。
- 「设备」页已配对列表里，有替换规则的设备也会在左侧显示 40dp 目标型号图，直观看到“会变成什么样”。

### 目标资源预取（避免“换了没反应”）

弹窗/超级岛/详情页的图都来自 `fc_resources/<目标代号>/`，如果这个目录还没下载，
取图 hook 会回退原图，看起来就像“替换失效”。

- 选型号/保存规则时，App 发广播 `io.github.jumaoli.budsid.PREFETCH` 给蓝牙模块，模块立刻后台下载目标 zip；
- App 每次回到前台也会对所有规则目标补一次预取；
- 下载完成后重连一次，弹窗和超级岛就是目标型号（实测 REDMI Clip Neo `01013F05`，336 个文件）。

### 代号库范围修正

小米的 `resource_record.xml` 里 **455 条**代号，其中 **217 条是 10 位**（外设：散热背夹、开放式耳机等）。
之前只收 8 位，所以外设全被过滤掉了。现在接受 8~12 位十六进制。

动画：CDN 上的 zip 都带 `fc_<id>_1000.webp` 帧，之前用 Range 探测大量 403（限流）导致误判“无动画”。
现在统一标记为有动画，避免漏标。

仍然没有的：小米 AI 眼镜不在小米蓝牙的代号表里（表中只有 3 款 Mijia 眼镜），
它只出现在小米耳机 App 的商品体系里，蓝牙侧拿不到。

### 本地资源包补全（小米 AI 眼镜）

小米云端 `00000000.zip` 只有 455 条，**不含 01013304（小米 AI 眼镜）**；
但这台手机上已经有它的本地包（`fc_resources/01013304/`，名字 `o95`，`fc_01013304_5000.webp`）。

- App 每次回到前台会向蓝牙模块发 `CATALOG_REQ`，模块把 `fc_resources/*` 的目录名回传；
- App 合并进代号库，并对已知代号做命名映射（`01013304 → 小米 AI 眼镜`）；
- 缩略图新增 `_5000` 兜底，眼镜也能显示主图。

### 预览图（含 CDN 403 的型号）

`fast_connect/<id>.zip` 对部分型号返回 403（私有对象），App 自己拉不到图。
现在加了一条兜底链路：

- App 先直连 CDN，失败后发广播 `io.github.jumaoli.budsid.THUMB_REQ`（带 id）给蓝牙模块；
- 模块在本机 `fc_resources/<id>/` 里找 `_hs_ok` / `_hs` / `_box` 图片，base64 回传；
- App 落盘到 `files/thumbs/<id>.webp` 并立即刷新对应 ImageView。

设备行只在有规则时显示目标图，没规则显示统一占位耳机图标；规则页每行也有目标图。

### 详情页大图预览 + 确定

点「设备」页某台已配对设备，弹层结构变成：

1. 顶部 150dp 大图，先显示当前目标型号（没有规则则占位）；
2. 搜索框 + 型号列表，点某一项只更新大图、不立即生效；
3. 底部「确定」按钮，点了才写入规则。

设置 → 界面 → 「详情页预览」可以关掉这块大图。

## 5. 已知限制

- 只换“身份”。目标型号若使用不同的私有协议，降噪/按键等功能页控制项可能点了没反应，
  音频播放和通话不受影响。
- 设备名显示的是蓝牙别名（如「小鞠的 Xiaomi Buds 5 Pro」），这是蓝牙栈里的别名，不随代号变。
- **设置页设备详情卡**的产品图来自 Settings/MiLink 的另一条链路，当前版本尚未验证能跟着换；
  弹窗、超级岛资源、代号已经打通。
- 按 MAC 限定的规则在拿不到 MAC 的调用点（弹窗取图、数据库查询）会退化成“按真实代号匹配”；
  多设备场景建议先只配一条规则。

## 6. 构建

不需要 Gradle，只用 JDK 17 + Android SDK build-tools：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

产物：`dist\budsid-v1.0.0.apk`，签名 `%USERPROFILE%\.budsid\budsid-debug.jks`（口令 android）。

> `build.ps1` 带 UTF-8 BOM（Windows PowerShell 5.1 读中文需要），其余源码不要加 BOM，
> 否则 javac 会报 `非法字符: '\ufeff'`。

## 7. 目录

```
AndroidManifest.xml        LSPosed 声明 + 配置 Provider
assets/xposed_init         io.github.jumaoli.budsid.hook.HookEntry
assets/devices.json        代号库（241 条，含是否有弹窗动画）
src/io/github/jumaoli/budsid/
  MainActivity.java        Miuix 风格界面
  MiuixSwitch.java         HyperOS 胶囊开关
  Sheet.java               底部弹层
  Theme.java               明暗配色
  Prefs.java / Rule.java   规则存储
  ConfigProvider.java      给注入进程读配置（files/override.json 可调试覆盖）
  Devices.java             代号库
  hook/HookEntry.java      hook 入口
  hook/Mapper.java         代号映射
```
