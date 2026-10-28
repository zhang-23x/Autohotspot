# AutoHotspot —— 手机自成热点 · Web 控制台

把一台闲置安卓手机变成**永不掉线的随身热点**：开机自动开启、掉线自己恢复，再用浏览器远程管着它。

不需要 root，不需要电脑常驻。手机自己跑一个 HTTP 服务（`192.168.43.1:8080`），连上热点的任何设备用浏览器就能打开控制台。

> 当前版本：**v62**（versionCode 62）
> 实测机型：**华为 / 荣耀 TEL-AN00a（HONOR X10）**，Android 10 / EMUI 11

---

## 一、适用机型

### 完全支持（已实测）

| 品牌 | 系统 | 说明 |
|---|---|---|
| 华为 / 荣耀 | EMUI 9 / 10 / 11 / 12，HarmonyOS 2 / 3 / 4 | 控制中心「个人热点」磁贴结构一致，无障碍自动点击可用 |
| **实测样机** | **HONOR X10（TEL-AN00a）** | Android 10，EMUI 11，已连续运行验证 |

### 理论可用（需自行适配）

其他安卓手机**基本功能能用**（Web 控制台、流量统计、定时任务、二维码），但这两项依赖具体系统 UI，可能需要改代码：

- **自动点击控制中心开热点** —— 小米 / OPPO / vivo / 三星 的快捷开关布局不同，要改 `HotspotService.java` 里的选择器
- **双卡切换** —— 华为用 `Settings.Global.multi_sim_data_call` 直写；其他厂商的键名不同

### 不支持

- Android 7.x 及以下（minSdk 26）
- iOS（无对应权限模型）

---

## 二、功能

### 总览

- 热点实时状态（AP 地址 / hostapd 进程）
- 电池电量、充电状态、电池温度（带内核热区判断）
- 访问设备数 / 上限
- 内存占用与可用量
- 移动网络类型（5G NR / 4G LTE）与运营商
- **双卡切换**：卡1 / 卡2 一键切换数据卡，无卡自动置灰
- 实时上下行速率
- 运行时长、累计流量、本月流量与限额进度

### 测速

- 270° 环形仪表盘 + 指针 + 对数刻度（0/5/10/25/50/100/150/300/500）
- 实时动画：测速过程中指针、进度弧、下载曲线、上传曲线同步刷新
- 三项指标：时延（TCP 三次探测取最快）、下载（走系统 DownloadManager，避免被限速）、实时上行
- 下载实测与华为浏览器同量级（34 ms / 100+ Mbps）

### 设置（分组折叠）

| 分组 | 内容 |
|---|---|
| 常用 | 热点名称与密码 |
| 安全 | 面板访问密码、拉黑设备、**定时任务** |
| 限额 | 每月流量上限、最大连接设备数 |
| 外观 | 深色 / 浅色 |
| 系统 | 检查热点状态、关闭热点、恢复自动守护、重启手机 |

- **面板访问密码**：只有「设置」页需要密码，总览 / 测速 / 记录 随便看。设了密码后页签上会显示 🔒
- **拉黑设备**：按 IP 或 MAC 拉黑，命中即重开热点把对方挤掉（本机无 root，无法直接封包）

### 分享

- 生成标准 WiFi 二维码（`WIFI:T:WPA;S:名称;P:密码;H:false;;`）
- iPhone 用相机直接扫就连；安卓扫码后仍需手动输一次密码（Android 10 起系统禁止第三方 App 代填 WiFi，属系统限制）
- 二维码由内嵌的 QR 编码器（version 4 / 纠错 L / 自动掩码）在前端实时绘制，不联网、不依赖外部库

### 记录

- 已连接设备表（IP / MAC / 备注，断开置灰，可点行命名）
- 24 小时流量曲线：Y 轴用量刻度、X 轴时间、曲线/柱形切换（记忆偏好）
- 热点开关运行记录（用来查「几点断过」）

### 自动化

- **开机自启**：`BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`
- **掉线自愈**：Watchdog 定时检查 hostapd，异常自动重开
- **定时任务**：到点重启热点 / 关闭热点 / 开启热点
- **限额保护**：本月流量超限自动断网

---

## 三、安装

### 需要准备

| 项 | 说明 |
|---|---|
| 安卓手机 | Android 8.0+（API 26），推荐华为/荣耀 |
| APK | 本仓库 `apk/autohotspot-v62.apk` |
| 电脑 + 数据线 | **仅首次需要**，用来跑一条 adb 命令授予系统级权限 |
| adb | [platform-tools](https://developer.android.com/studio/releases/platform-tools)，或用手机助手自带的 |

### 步骤

**1. 安装 APK**

把 `apk/autohotspot-v62.apk` 传到手机，点安装。系统会提示「未知来源」，允许即可。

或用 adb：

```bash
adb install -r -t autohotspot-v62.apk
```

> 华为/荣耀会弹「风险提示 → 继续安装」，点「继续安装」。

**2. 授予 `WRITE_SECURE_SETTINGS`（关键一步）**

这个权限不能靠点按授予，必须走 adb。**没有它，App 重启后无法自己恢复无障碍授权，开机自启会失效。**

```bash
adb shell pm grant com.yg.autohotspot android.permission.WRITE_SECURE_SETTINGS
```

**3. 开启无障碍服务**

设置 → 辅助功能 → 无障碍 → 已安装的服务 → **AutoHotspot** → 开启

> 这一步是让 App 能模拟点击「个人热点」开关。关掉它，自动开热点就失效。

**4. 加入电池优化白名单**

设置 → 电池 → 应用启动管理 → AutoHotspot → 改为**手动管理**，并允许：自启动、关联启动、后台活动

或让 App 自己申请：

```bash
adb shell cmd deviceidle whitelist +com.yg.autohotspot
```

**5. 打开控制台**

手机连上自己的热点（或同网段设备），浏览器访问：

```
http://192.168.43.1:8080
```

> 端口固定 8080。手机 IP 一般是 `192.168.43.1`，以「总览」页显示为准。

**6.（可选）设备所有者模式**

想要「重启手机」这类系统级操作，需要把 App 设为 Device Owner。**只在手机未登录任何账号、刚恢复出厂时可行**：

```bash
adb shell dpm set-device-owner com.yg.autohotspot/.AdminReceiver
```

不设也能用，只是「重启手机」按钮无效。

---

## 四、构建（想自己改代码）

### 依赖

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 8+ | 编译 Java |
| Android SDK build-tools | 含 `aapt2` / `d8` / `apksigner` | 打包 |
| Node.js | 任意 | 跑 `chk58.js` 做前端语法自检 |

**没有 Gradle、没有任何第三方库**——纯 `aapt2 + javac + d8 + apksigner` 手搓，所以构建脚本很短。

### 构建

```powershell
cd tools
.\build.ps1
```

⚠️ **aapt2 打不开含中文的路径**。构建目录必须是纯 ASCII（如 `C:\ahbuild`），否则会报 `failed to open directory`。

### 改完前端必做

`PhoneServer.java` 里整个页面是一个巨大的 Java 字符串常量，改错一个引号就白屏。改完跑：

```powershell
node chk58.js
```

输出 `JS SYNTAX OK` 才算过。

---

## 五、目录结构

```
Autohotspot/
├── apk/
│   └── autohotspot-v62.apk          已编译可安装包
├── app/
│   ├── AndroidManifest.xml
│   ├── res/                          字符串、无障碍配置、设备管理员配置
│   └── src/com/yg/autohotspot/
│       ├── PhoneServer.java          内置 HTTP 服务 + 整个 Web 控制台页面（约 3400 行）
│       ├── HotspotService.java       无障碍服务：自动开热点、掉线自愈、切卡
│       ├── Panel.java                配置读写（SharedPreferences）
│       ├── PanelService.java         前台服务，维持 HTTP 服务存活
│       ├── SimInfo.java              双卡信息读取与切换
│       ├── Stats.java                流量统计
│       ├── History.java              运行记录
│       ├── Automation.java           定时任务
│       ├── SelfHeal.java             自愈逻辑
│       ├── UiFinder.java             无障碍节点查找工具
│       ├── ApProfile.java            热点配置
│       ├── Reboot.java               重启
│       ├── BootReceiver.java         开机广播
│       ├── WatchdogReceiver.java     看门狗
│       ├── AdminReceiver.java        设备管理员
│       └── MainActivity.java         入口界面
├── tools/
│   ├── build.ps1                     构建脚本
│   ├── install.ps1                   安装脚本（自动点「继续安装」）
│   ├── chk58.js                      前端 JS 语法自检
│   └── debug.keystore                测试签名（密码 android）
└── docs/
    └── 更新记录_v62.txt
```

---

## 六、已知限制

| 限制 | 原因 |
|---|---|
| 读不到热点名称/密码 | Android 10 起 `getWifiApConfiguration()` 返回 null，需在「分享」页手填一次 |
| 安卓扫码不能自动连 | Android 10 禁止第三方 App 调 `addNetwork()`，只能由用户手动确认 |
| 拉黑只能「挤下线」 | 无 root，无法直接封 MAC，靠重开热点把对方踢掉 |
| 测速只跑 10 秒、烧约 39 MB | 时间太长用户等不了，流量也是成本 |
| 下载速度只能测单连接 | 走系统 DownloadManager，多连接并发需要自己写调度 |

---

## 七、版本

- **v62** —— 设置页折叠分组、定时任务并入设置、页签「定时」→「分享」、WiFi 二维码
- **v61** —— 测速改用系统 DownloadManager，解决第三方 App 被限速（0.5 Mbps → 100+ Mbps）
- **v60** —— 环形仪表盘测速页、实时动画、曲线 X 轴时间、拉黑设备
- **v59** —— 去掉全局锁改为只锁设置、设备列表移入记录页、测速功能
- **v58** —— 流量曲线 Y 轴刻度 + 柱形切换、内存显示可用量
- **v57** —— 双卡切换（直写 `multi_sim_data_call`）
- **v56** —— 双卡信息读取

---

## 八、免责

本工具会修改系统无线设置、使用无障碍服务模拟点击。请只在自己拥有、且愿意承担风险的设备上使用。

不同厂商 ROM 行为差异很大，出问题先看 `adb logcat`。

MIT License.
