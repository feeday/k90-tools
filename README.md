# K90-Tools（实验整合版）

面向 **Redmi K90 / HyperOS / Android 16** 的性能监控与 AI 工具整合 APK。

当前版本：**5.9-tools**

项目核心目标：**普通权限、无需 Root、无需 Shizuku，也能稳定显示 K90 的系统性能、温度和实时网速。**

当前还集成：

- CPU 压力测试
- 局域网 FTP 文件传输
- FTP 后台常驻传输
- 悬浮窗内 FTP 快速开关
- 悬浮窗显示 FTP 地址 / 连接数 / 上传下载速率
- APK 内置 Puck Chat AI 工具箱（不依赖 puck.chat 网站）
- 仅保留豆包 TTS 语音朗读和本地 TTS，不包含实时 ASR、麦克风录音或麦克风权限。
- 可选红魔散热器遥测扩展
- Android 15 / 16 Edge-to-Edge 安全区适配

红魔散热器属于可选扩展。即使没有红魔散热器、没有连接红魔官方 App、没有运行任何桥接，K90 系统悬浮监控和 CPU 压力测试仍然可以独立使用。

---

## 主要功能

### K90 系统监控

- CPU 实时总占用率
- CPU 当前最高核心频率
- CPU / CPUSS 温度
- GPU / GPUSS 温度
- BAT 电池温度
- RAM 已用 / 总内存 / 占用率
- 实时下载速度
- 实时上传速度
- 默认约 1 秒刷新一次
- 半透明悬浮窗
- 悬浮框可拖动
- 长按悬浮框关闭
- 全部模式下可直接开 / 关 FTP
- FTP 运行时显示地址、连接数和实时传输速率
- Android 16 前台服务
- 无需 Root
- 无需 Shizuku
- 无需连接任何外设

> 已移除屏幕刷新率 / 帧率显示。普通 APK 无法可靠读取其他游戏的真实渲染 FPS，因此不再用 `120 Hz` 冒充游戏帧率。

## APK 内置 Puck Chat AI 工具箱

本仓库是独立实验项目，原版 `K90PerformanceOverlay` 已保持在实验前稳定版本。此 APK 使用独立包名 `com.ppt.k90tools`，可以与原 K90 并存安装。

Puck Chat 页面已经作为本地资源打包进 APK：

- 主界面可点击“打开 Puck Chat AI 工具箱”。
- 悬浮窗“全部模式”底部增加“AI 工具箱”入口。
- 页面从 APK 的 assets 加载，不访问 `https://puck.chat/`，也不需要部署 Node.js 网站。外部网站跳转按钮已移除，WebView 也会拦截外部页面导航。
- 对话记忆、模型 Key 和语音设置继续保存在 WebView 本地存储中。
- 支持文本模型、图像模型、文本处理、二维码、图片元数据、视频抽帧、音频剪辑等原页面工具。
- 语音只保留豆包 TTS 朗读，并支持本地 TTS：`http://127.0.0.1:8080/api/tts` 和 `http://192.168.31.11:8080/api/tts`。
- 不提供麦克风实时语音输入，也不申请麦克风权限。

**网络说明：** 不需要购买或配置 puck.chat 域名，也不需要在手机上运行 Node.js。但在线模型、豆包 TTS 和页面使用的第三方 JS 库仍然需要互联网连接及相应服务的 API Key；本地 TTS 需要手机能访问所选本地服务。

---

## 两种悬浮显示模式

应用支持两种悬浮窗显示模式，并会记住上一次选择。

### 温度模式

温度模式为 **极简单行悬浮窗**。

显示顺序：

```text
C:CPU温度  G:GPU温度  B:电池温度  B:背夹温度
```

示例：

```text
C:60  G:30  B:30  B:18
```

对应含义：

- `C`：CPU 温度
- `G`：GPU 温度
- 第一个 `B`：BAT 电池温度
- 第二个 `B`：红魔背夹 / CLAMP 温度

温度模式特点：

- 只显示一行
- 不显示 `K90 MONITOR` 标题
- 不显示“长按关闭”提示文字
- 不显示 FTP 开关
- 不显示 `°C`
- 不显示小数，四舍五入为整数
- 悬浮窗仍然可以拖动
- 长按关闭功能仍然保留，只是不显示提示文字
- **某项没有有效数据时整项隐藏，不显示 `--`**

例如背夹没有实时数据时：

```text
C:60  G:30  B:30
```

如果 GPU 也没有有效数据：

```text
C:60  B:30
```

### 全部模式

全部模式已经移除顶部 `K90 MONITOR` 标题，主体直接显示监控数据：

```text
CPU  10.6%  1.79GHz  40.1°C
GPU  33.0°C   BAT 33.3°C
RAM  7.1G / 14.8G  48.3%
NET ↓0B/s  ↑117B/s
```

如果检测到红魔实时数据，会自动追加：

```text
REDMAGIC
FAN 6520 RPM   CLAMP -3.0°C
PWR 37 W
```

FTP 未开启时底部控制区：

```text
FTP:关    长按关闭
```

FTP 开启后会额外显示：

```text
ftp://192.168.1.100:2121
连接 1  ↓12.4MB/s  ↑3.1MB/s
FTP:开    长按关闭
```

其中：

- `ftp://...`：当前 FTP 局域网地址
- `连接 N`：当前 FTP 控制连接数量
- `↓`：FTP 客户端从手机 **下载** 文件的实时速率
- `↑`：FTP 客户端向手机 **上传** 文件的实时速率
- 没有文件传输时速率回落到 `0B/s`
- FTP 速率只统计 FTP 文件数据，不使用系统总网速冒充
- 点击 `FTP:开 / 关` 可切换 FTP 服务
- 长按悬浮框仍然可以关闭悬浮监控
- FTP 状态约每秒刷新

---

## CPU 压力测试

当前压力测试只保留 **CPU**。

GPU 压力测试和 CPU + GPU 双烤已移除，原因是当前 K90 / HyperOS 普通 APK 无法稳定读取 GPU 占用率和实时频率，单纯制造 GPU 高负载缺少足够直观的可对比结果。

CPU 压力测试支持：

```text
3 分钟
5 分钟
15 分钟
30 分钟
60 分钟 / 1 小时
```

这五档分别适合：

- 3 分钟：快速测试，观察初期温升和降频
- 5 分钟：短时稳定性测试
- 15 分钟：日常散热能力对比
- 30 分钟：长时间持续性能测试
- 60 分钟：极限耐久 / 散热测试

### 测试方式

开始测试后会根据：

```text
Runtime.getRuntime().availableProcessors()
```

创建对应数量的 CPU 工作线程，并持续执行整数与浮点计算，使 CPU 保持高负载。

测试期间约每秒采样一次。

CPU 频率优先读取：

```text
/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq
```

读取失败时回退：

```text
/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_cur_freq
```

再失败时回退到现有系统监控可读取的 CPU 频率。

### 测试结果

每次采样会读取所有可读在线 CPU 核心频率。

测试结束会显示：

- CPU 平均频率
- CPU 最高频率
- CPU 最低频率
- CPU 频率采样次数
- CPU 平均占用率
- CPU 最高温度
- BAT 最高温度
- 实际测试时长

当前版本已经取消 CPU 频率曲线，结果页面只保留最直接的统计数字，减少 UI 占用和无效信息。

### 温度保护

为了避免异常高温持续烧机，CPU 压力测试包含自动保护：

```text
CPU >= 105°C  -> 自动停止
BAT >= 55°C   -> 自动停止
```

离开压力测试页面时也会停止当前压力负载。

---

## FTP 文件传输

内置 **局域网 FTP Server**，可以直接在手机和电脑之间传文件。

FTP 与性能监控服务相互独立，目前支持：

1. 在 APP 主界面启动 / 停止 FTP
2. 在“全部模式”悬浮窗底部点击 `FTP:开 / 关`
3. FTP 启动后切换到其他 App 继续后台运行
4. 悬浮窗实时显示 FTP 地址、连接数和上传 / 下载速率

### 默认设置

```text
端口：2121
用户名：k90
密码：123456
```

端口、用户名和密码都可以在应用主界面修改。

悬浮窗启动 FTP 时，会使用主界面已经保存的端口、用户名和密码。

启动后会显示当前 FTP 地址，例如：

```text
ftp://192.168.1.100:2121
```

电脑和手机需要处于可以互相访问的同一局域网中。

### FTP 后台运行

FTP 已按后台传输场景进行保活：

- 使用 Android 前台服务
- `stopWithTask=false`
- 切换其他 App 不主动停止 FTP
- 使用 `PARTIAL_WAKE_LOCK` 保持传输期间 CPU 可运行
- 使用 Wi-Fi 高性能锁减少后台 Wi-Fi 休眠对局域网 socket 的影响
- FTP 服务与悬浮监控服务彼此独立

正常预期：

```text
在 K90 应用内启动 FTP
        ↓
切换到游戏 / 浏览器 / 桌面 / 其他 App
        ↓
FTP 服务继续监听 2121 端口
        ↓
电脑仍可继续连接和传输
```

### Android 12+ 后台启动限制

Android 12 及更高版本限制应用在已经处于后台时随意启动新的前台服务。

因此最稳定的使用流程是：

```text
先打开 K90 应用
        ↓
启动 FTP
        ↓
确认 FTP:开
        ↓
再切换到其他 App
```

如果 FTP 尚未启动，而应用已经完全进入后台，此时再从悬浮窗第一次启动 FTP，系统仍可能拒绝该前台服务启动。

当前悬浮开关已经增加异常保护：

```text
后台启动被系统拒绝 -> 显示提示，不允许整个应用 / 悬浮窗闪退
```

关闭 FTP 改为直接停止现有服务，不再通过“重新启动前台服务再发送停止命令”的方式处理。

### FTP 连接与速率统计

FTP 服务内部维护独立统计：

- 当前控制连接数量
- `RETR`：客户端下载字节数
- `STOR`：客户端上传字节数
- `APPE`：客户端追加上传字节数

悬浮窗约每秒读取累计字节差值计算速率。

例如：

```text
连接 2  ↓35.8MB/s  ↑0B/s
```

这里表示当前有 2 个 FTP 控制连接，并且客户端正在从手机读取文件。

### 支持功能

- 启动 FTP
- 停止 FTP
- 后台持续运行
- 悬浮窗快速开 / 关 FTP
- 显示当前 FTP 地址
- 显示当前连接数
- 显示 FTP 上传 / 下载实时速率
- 一键复制 FTP 地址
- 用户名 / 密码认证
- 文件上传
- 文件下载
- 文件夹浏览
- 新建目录
- 删除文件
- 删除空目录
- 文件 / 目录重命名
- `SIZE`
- `MDTM`
- `REST` 断点读取
- PASV 被动模式
- EPSV 扩展被动模式

可使用：

```text
FileZilla
WinSCP
Windows / Android FTP 客户端
其他标准 FTP 客户端
```

### FTP 共享目录

如果 Android 11+ 已授予“所有文件访问”权限：

```text
FTP 根目录 -> 手机内部存储根目录
```

如果没有授予“所有文件访问”权限：

```text
FTP 根目录 -> 本应用自己的 ftp 文件夹
```

也就是说，不授权全部存储时，FTP 仍然可以使用，只是访问范围会被限制在应用目录内。

### FTP 安全说明

FTP 本身是明文协议，用户名、密码和文件内容不会像 SFTP / HTTPS 那样加密。

建议：

- 只在可信局域网中使用
- 使用后及时停止 FTP
- 不要长期使用默认密码 `123456`
- 不建议直接把 FTP 端口暴露到公网

---

## Android 15 / 16 系统栏适配

项目 `targetSdk` 为 Android API 36。

Android 15 / 16 会更积极地启用 Edge-to-Edge，普通页面如果没有处理系统 Insets，容易出现：

- 顶部标题被状态栏 / 挖孔区域覆盖
- 底部按钮被手势导航条覆盖
- 横屏左右内容贴到系统导航区域

当前已经增加全局安全区处理：

- 状态栏 Insets
- 导航栏 Insets
- Display Cutout / 挖孔区域
- 横屏左右安全区域

并修复了一次安全区适配引起的运行时闪退。

之前的问题代码使用了系统资源 ID 作为 `View.setTag(int, Object)` 的 key，在部分 Android / HyperOS 设备上可能抛出 `IllegalArgumentException`。

当前实现已移除该危险标记逻辑，并增加异常保护：

```text
安全区适配失败 -> 不允许导致整个 Activity 闪退
```

### 系统状态栏文字说明

普通 APK 无法直接把自定义 CPU 温度 / 频率文字写进 Android `SystemUI` 的真正状态栏区域。

如果以后增加“状态栏模式”，实现方式会是顶部极细悬浮层模拟状态栏显示，而不是修改 SystemUI 本身。真正修改系统状态栏通常需要 Root / LSPosed / 系统级权限。

---

## 红魔散热器扩展

当前针对 **REDMAGIC Cooler 8 Pro / 红魔散热背夹 8 Pro** 做了遥测适配。

支持显示：

- 背夹温度
- 风扇转速 RPM
- 实时功耗 W

红魔数据采用**条件显示**：

- 没有实时 RPM 时，悬浮窗不显示无效红魔数据
- 检测到有效且未过期的 RPM 后，自动显示红魔数据
- 红魔数据中断或过期后，相关数据自动隐藏
- 红魔数据异常不会影响 K90 系统监控
- 温度模式下没有背夹温度时，第二个 `B` 整项隐藏

### 负温度支持

已确认支持负温度，例如：

```text
-1°C
-2°C
-3°C
```

红魔官方日志实测会出现：

```text
Jacket8ProViewModel onTemperature values=[-2]
Jacket8ProViewModel onTemperature values=[-3]
```

同时原始数据也能看到：

```text
[fe] -> -2°C
[fd] -> -3°C
```

桥接脚本直接读取：

```text
Jacket8ProViewModel onTemperature values=[...]
```

避免被其他中间状态温度字段覆盖成 `0°C`。

---

## 红魔数据工作方式

当前稳定使用方式仍然是由红魔官方 App 负责：

```text
BLE 连接
设备鉴权
散热器控制
官方数据解析
```

官方 App 连接散热器后会输出类似日志：

```text
Jacket8ProViewModel onTemperature values=[-3]
Jacket8ProViewModel onFanSpeed value=6520
Jacket8ProViewModel onFanPower value=37
```

桥接脚本将最新结果写入：

```text
/sdcard/Android/data/com.ppt.k90monitor/files/redmagic_metrics.txt
```

文件格式：

```text
TEMP=-3
RPM=6520
POWER=37
UPDATED=1787980000
```

APK 读取这个文件并显示实时遥测数据。

### 为什么普通 APK 不能直接读取红魔 App 日志

Android 16 下，普通第三方 APK 无权读取其他 App 的完整 logcat。

项目曾尝试 `READ_LOGS`，但当前 K90 / HyperOS 环境下无法通过普通 `pm grant` 为第三方 APK 获得该权限。

因此当前稳定方案为：

```text
K90 系统监控      -> 普通 APK 独立运行
红魔官方日志读取   -> 需要 shell/root 级桥接
```

这不会影响普通的 CPU / GPU 温度 / BAT / RAM / NET 监控，也不会影响 CPU 压力测试。

---

## 红魔桥接脚本

应用启动后会自动生成：

```text
/sdcard/Android/data/com.ppt.k90monitor/files/redmagic_bridge.sh
```

目前可通过 AYA shell 等具有 shell 权限的环境运行。

桥接带有单实例保护：

- 启动新版桥接前自动结束旧 `redmagic_bridge.sh`
- 清理旧 metrics / tmp 文件
- 使用 PID 文件避免多个桥接实例同时运行
- 避免多个旧脚本互相抢写 `TEMP=0`

PID 文件：

```text
/sdcard/Android/data/com.ppt.k90monitor/files/redmagic_bridge.pid
```

启动命令可以直接在 APP 中复制。

等价命令示例：

```sh
pkill -f '[r]edmagic_bridge.sh' 2>/dev/null || true; sleep 1; rm -f /sdcard/Android/data/com.ppt.k90monitor/files/redmagic_metrics.txt /sdcard/Android/data/com.ppt.k90monitor/files/redmagic_metrics.tmp /sdcard/Android/data/com.ppt.k90monitor/files/redmagic_bridge.pid; nohup sh /sdcard/Android/data/com.ppt.k90monitor/files/redmagic_bridge.sh >/sdcard/Android/data/com.ppt.k90monitor/files/redmagic_bridge.log 2>&1 &
```

如果怀疑数据不正确，可以直接检查：

```sh
cat /sdcard/Android/data/com.ppt.k90monitor/files/redmagic_metrics.txt
```

例如官方 App 显示 `-3°C` 时，应看到：

```text
TEMP=-3
RPM=6520
POWER=37
```

---

## K90 实机适配

当前主要针对以下设备环境开发与测试：

```text
Device: annibale
Model: 2510DRK44C
SoC: Qualcomm SM8750
Platform: sun
Android API: 36
HyperOS: Android 16
```

当前数据支持情况：

| 指标 | 状态 |
| --- | --- |
| CPU 占用率 | ✅ 可读取 |
| CPU 频率 | ✅ 可读取 |
| CPU / CPUSS 温度 | ✅ 可读取 |
| GPU / GPUSS 温度 | ✅ 可读取 |
| RAM | ✅ 可读取 |
| 电池温度 | ✅ 可读取 |
| 实时下载速度 | ✅ 可读取 |
| 实时上传速度 | ✅ 可读取 |
| CPU 压力测试 | ✅ 3 / 5 / 15 / 30 / 60 分钟 |
| FTP 局域网文件传输 | ✅ 可用 |
| FTP 后台运行 | ✅ 前台服务 + WakeLock + Wi-Fi Lock |
| FTP 当前连接数 | ✅ 可显示 |
| FTP 实时上传 / 下载速率 | ✅ 可显示 |
| 悬浮窗 FTP 开关 | ✅ 全部模式可用 |
| GPU 占用率 | ❌ 普通 APK 被 SELinux 限制 |
| GPU 频率 | ❌ 普通 APK 被 SELinux 限制 |
| GPU 压力测试 | ❌ 已移除 |
| 游戏真实 FPS | ❌ 普通 APK 无法可靠获取 |
| 真正 SystemUI 状态栏自定义文字 | ❌ 普通 APK 无权限 |
| 红魔背夹温度 | ⚙️ 可选扩展 |
| 红魔风扇 RPM | ⚙️ 可选扩展 |
| 红魔功耗 | ⚙️ 可选扩展 |

---

## 系统数据读取方式

K90 系统数据主要通过 Android 公共 API 和普通应用可访问的系统节点读取：

- RAM：`ActivityManager.MemoryInfo`
- 电池温度：`ACTION_BATTERY_CHANGED`
- 网络流量：`TrafficStats.getTotalRxBytes()` / `getTotalTxBytes()`
- CPU 占用：多级回退
  - `/proc/stat`
  - CPU idle 累计时间差值
  - Android `top` 输出回退
- CPU 频率：`/sys/devices/system/cpu/cpu*/cpufreq/`
- CPU / GPU 温度：动态扫描 `/sys/class/thermal/thermal_zone*`

实时网速通过总收发字节增量计算：

```text
NET ↓26.8MB/s  ↑3.2MB/s
```

FTP 自身的速率统计独立于 `NET`：

```text
NET -> 手机系统总网络流量
FTP -> 仅 RETR / STOR / APPE 文件数据流量
```

---

## GPU 占用率与频率为什么不显示

K90 上 Qualcomm KGSL 相关节点实际存在，例如：

```text
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
/sys/class/kgsl/kgsl-3d0/gpubusy
/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load
/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
/sys/class/kgsl/kgsl-3d0/clock_mhz
/sys/class/kgsl/kgsl-3d0/gpuclk
```

但在当前 HyperOS / Android 16 环境下，普通第三方 APK 对这些节点通常表现为：

```text
EXISTS=true
READ=false
```

因此项目不显示无意义的 GPU `N/A` 占用率和频率，只保留能够稳定读取的 GPU 温度。

```text
CPU/GPU thermal 温度    -> 普通 APK 可读取
GPU utilization / clock -> 当前 HyperOS 普通 APK 不可读取
```

这也是当前移除 GPU 压力测试、重点保留 CPU 压力测试的主要原因。

---

## 使用方法

### 只使用 K90 系统监控

1. 安装 APK
2. 打开应用
3. 授予“显示在其他应用上层 / 悬浮窗”权限
4. 选择“温度模式”或“全部模式”
5. 点击“开始悬浮监控”
6. 拖动悬浮框调整位置
7. 长按悬浮框关闭

不需要：

```text
Root
Shizuku
READ_LOGS
蓝牙权限
红魔官方 App
红魔散热器
AYA
```

### 使用 CPU 压力测试

1. 打开主应用
2. 找到“CPU 压力测试”
3. 点击“进入 CPU 压力测试”
4. 选择 3 / 5 / 15 / 30 / 60 分钟
5. 点击“开始 CPU 压力测试”
6. 页面实时显示 CPU 占用、平均频率、CPU 温度、电池温度
7. 测试完成后查看平均 / 最高 / 最低频率及温度统计

### 使用 FTP

推荐方式：

1. 打开应用
2. 找到“FTP 文件传输”
3. 根据需要修改端口、用户名和密码
4. 如需访问整个内部存储，授予“所有文件访问”权限
5. 在 APP 仍处于前台时点击启动 FTP
6. 确认状态显示 `FTP:开`
7. 复制 FTP 地址
8. 此后可以切换到游戏、桌面或其他 App
9. 在电脑 FTP 客户端中输入地址并登录

悬浮窗会显示：

```text
ftp://192.168.1.100:2121
连接 1  ↓0B/s  ↑0B/s
FTP:开    长按关闭
```

如果已经后台且 FTP 尚未启动，首次从悬浮窗启动可能被 Android 12+ 的后台前台服务限制拦截；此时重新打开主应用启动即可。

默认示例：

```text
地址：ftp://192.168.1.100:2121
用户名：k90
密码：123456
```

### 使用红魔散热器扩展

1. 安装并打开最新版 APK 一次，让应用生成桥接脚本
2. 打开红魔官方 App
3. 让官方 App 正常连接散热器
4. 确认官方 App 能显示背夹温度 / RPM / 功耗
5. 在具有 shell 权限的环境中启动桥接命令
6. K90 悬浮窗检测到有效 RPM 后自动显示红魔数据

如果散热器断开或数据停止更新，红魔相关数据会自动隐藏，系统监控继续运行。

---

## HyperOS 后台建议

FTP 已加入前台服务、WakeLock 和 Wi-Fi Lock 保活，但 HyperOS 仍可能在激进省电策略下限制第三方后台服务。

如果锁屏或长时间后台后悬浮服务或 FTP 服务仍被系统回收，建议在系统应用管理中：

- 允许后台运行
- 关闭本应用省电限制
- 必要时允许自启动
- 不要手动“强行停止”应用

正常的“切换到其他 App / 返回桌面”不应该主动停止 FTP。

---

## GitHub Actions 一键构建 APK

仓库已经配置 `Build APK` workflow，不需要在电脑安装 Android Studio。

构建流程会：

- Checkout 最新 `main`
- 输出当前 commit / versionCode / versionName
- 安装 Android 16 SDK
- 执行 `clean assembleDebug`
- 生成 APK SHA256
- 生成 `BUILD_INFO.txt`
- 上传 Artifact：`K90PerformanceOverlay-APK`

使用方法：

1. 打开仓库顶部 `Actions`
2. 选择 `Build APK`
3. 点击 `Run workflow`
4. 等待构建显示 `Success`
5. 打开对应构建记录
6. 在 `Artifacts` 下载 `K90PerformanceOverlay-APK`

Artifact 中包含：

```text
K90PerformanceOverlay-debug.apk
K90PerformanceOverlay-debug.apk.sha256
BUILD_INFO.txt
```

---

## 本地构建

推荐环境：

```text
JDK 17
Android SDK 36
Android Build Tools 36.0.0
Gradle 8.13
```

Android Studio：

```text
Build -> Build APK(s)
```

默认输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 权限原则

K90 系统监控坚持尽量使用普通 Android 权限和公开可访问数据源。

基础系统监控和 CPU 压力测试不要求：

```text
Root
Shizuku
READ_LOGS
蓝牙权限
红魔官方 App
红魔散热器
```

FTP 后台保活使用：

```text
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
WAKE_LOCK
ACCESS_WIFI_STATE
INTERNET
```

其中 WakeLock / Wi-Fi 状态权限属于普通应用权限，不需要 Root。

FTP 如果只使用应用自己的 FTP 目录，不需要“所有文件访问”；只有需要通过 FTP 管理整个内部存储时才需要额外授权 `MANAGE_EXTERNAL_STORAGE`。

红魔遥测属于可选功能，只在存在有效桥接数据时显示。

---

## 最近更新

### 2026-09-05

- 压力测试改为 **CPU-only**
- 移除 GPU 压力测试
- 移除 CPU + GPU 双烤
- CPU 测试时长调整为 **3 / 5 / 15 / 30 / 60 分钟**
- 保留 CPU 平均 / 最高 / 最低频率统计
- 保留 CPU 平均占用、最高温度、电池最高温度统计
- **取消 CPU 频率曲线图**，结果页只保留关键数字
- 全部模式移除 `K90 MONITOR` 顶部标题
- 全部模式底部改为 `FTP:开/关    长按关闭`
- 悬浮窗可直接启动 / 停止 FTP
- FTP 开启后显示局域网地址
- FTP 开启后显示当前控制连接数
- FTP 开启后显示独立的上传 / 下载实时速率
- FTP 速率按 `RETR / STOR / APPE` 实际传输字节统计
- FTP 增加后台常驻支持
- FTP 服务设置 `stopWithTask=false`
- FTP 增加 `PARTIAL_WAKE_LOCK`
- FTP 增加 Wi-Fi 高性能锁
- 修正悬浮窗停止 FTP 的服务调用方式
- 增加 Android 12+ 后台前台服务启动异常保护，避免切换 FTP 导致应用闪退
- 温度模式继续保持纯单行显示
- 单 APP / 单桌面图标，压力测试从主界面进入
- 增加 Android 15 / 16 状态栏、导航栏、挖孔安全区适配
- 修复安全区 `View.setTag()` 使用系统资源 ID 导致的运行时闪退
- README 补充真正 SystemUI 状态栏无法由普通 APK 自定义文字的说明

---

## 安装安全提示

GitHub Actions 当前主要生成 **debug 签名 APK**，适合个人设备侧载、测试和开发验证。

如果未来正式发布，应使用独立 release keystore 签名，并安全保管签名材料。


## 仓库与分支约定

- `main`：原 K90 稳定功能与 AI 工具集成后的可构建版本。
- `experiment`：新功能、UI 调整、兼容性和 Bug 修复的实验分支；验证通过后再合并到 `main`。
- 原仓库 `feeday/K90PerformanceOverlay` 已恢复到本次实验开始前的 5.7 版本，不在原仓库继续做实验。
- 新 APK applicationId：`com.ppt.k90tools`，不会覆盖原 K90 的 `com.ppt.k90monitor`。

## K90-Tools 5.10 更新

- 音频剪辑导出、视频抽帧 ZIP、视频音轨提取、二维码 PNG、对话 JSON 统一通过 Android 原生文件接口保存。
- Android 10 及以上默认保存到手机 Download/K90-Tools/；较旧 Android 保存到应用专属 Downloads/K90-Tools 目录。保存后页面会显示实际路径。
- AI 对话每轮自动更新 Download/K90-Tools/ai_chat_memory.json，同时保留 WebView 本地记忆；“导出记忆”另存带时间戳的 JSON 文件。
- 性能悬浮窗新增输入框。发送后打开同一个 K90-Tools 工具箱实例，要求先配置文本模型 API 地址 / API Key；文本回复自动使用当前选定的豆包或本地 TTS 朗读。
- 工具箱 Activity 使用 singleTask，悬浮窗和主界面重复点击不会创建多个工具箱页面。
- 网络 Ping 独立放在工具页面最底部；节点切换只用于连通性检测，不会打开外部网页。
- 页面只保留内置功能按钮；外部网站入口不再显示。



### 5.13 AI 调用与悬浮输入修复

- AI JSON 请求通过 Android 原生 OkHttp 异步执行，支持本地 HTTP 和云端 HTTPS，避免 WebView 的 CORS / 混合内容拦截；HTTP 状态和接口错误继续显示。
- 自定义本地模型的 API Key 可留空；悬浮窗在请求期间不会覆盖正在处理的输入。
- 全部模式使用屏幕内的 320dp 输入布局；温度模式隐藏输入框、操作栏及 FTP 信息，切换时收起键盘。
- 网络行为回归检查：`node --test tests/puck-network.test.cjs`。真实模型服务及手机悬浮窗仍需设备验证。

### 5.14 云端调用诊断与朗读过滤

- 发送按钮显示等待秒数，失败弹出错误；增加 API 检测按钮查询模型列表，区分鉴权、模型配置与对话服务问题。原生网络错误区分 DNS、TLS、连接失败和超时。
- 粘贴包含 Bearer 前缀的 Key 时避免重复前缀。
- 本地、豆包和系统朗读统一过滤 think / thinking / analysis / reasoning 区块（包括未闭合区块）、代码、URL、数字和符号，只保留正文中文与英文字母。
- 9 项回归测试通过。无密钥云端探测已收到鉴权错误，但未取得用户实际失败请求，尚不能确认其云端故障根因或宣称修复完成。

### 5.15 调用用量日志

新增独立的最近 30 次会话内调用日志，LLM 与 TTS 不互相覆盖。LLM 显示响应模型、请求耗时、输入/输出/总 Token、请求平均输出速度及接口费用；估算 Token 明确标记。TTS 显示引擎、资源/模型、音色、过滤后提交字符数、合成耗时、音频大小及解码后的音频时长。系统语音仅提供朗读总耗时，不能推算音频时长。未知费用或接口用量显示未提供，不按字符伪造计费。

豆包会话结束返回的 usage 转交页面显示。系统语音无 Key 路径修复 pending 状态，确保完成回调可更新日志。10 项回归测试通过。

### 5.16 简化界面与图标

移除 API 检测按钮及其事件处理。启动图标改为深蓝灰底、白色 K 字标，主题单色图标使用相同形状。保留调用日志和等待状态。

### 5.17 图像描述与精简结果区

调用日志默认折叠并显示绿色文字；UPLINK SECURED 下只显示模型回复，耗时和用量保留在独立日志中。移除二维码和视频提取音频按钮及对应处理。新增图像描述按钮，通过当前对话模型发送当前图片和详细描述提示词，不附加历史对话；需要支持图像输入的模型。

### 5.18 豆包朗读与悬浮窗精简

删除悬浮窗输入框、发送和键盘焦点处理，保留监控及 AI 工具箱入口。图标替换为炭灰底白色工具箱轮廓。豆包请求增加代次隔离，避免旧连接回调、旧超时任务及排队回调干扰新请求；停止时立即取消连接，捕获请求初始化异常，握手失败显示 HTTP 状态及请求编号。未使用用户实际密钥验证线上合成。
