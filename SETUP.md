# 录屏回放 App(ReplayRecoder)环境配置与编译指南

> 测试目标机:鸿蒙4(HarmonyOS 4,底层兼容层 ≈ Android 12,可正常安装 Android APK)
> 最低编译环境:JDK 17 + Android Studio + Android SDK 34

## 一、装软件(顺序执行)

### 1. 装 JDK 17
- 下载 Oracle JDK 17 或微软 OpenJDK 17:<https://learn.microsoft.com/java/openjdk/>
- 装完设环境变量(此电脑 → 属性 → 高级系统设置 → 环境变量 → 系统变量):
  - 新建 `JAVA_HOME` = JDK 安装路径(如 `C:\Program Files\Microsoft\jdk-17.0.x.x-hotspot`)
  - `Path` 里加 `%JAVA_HOME%\bin`
- 验证:新开 PowerShell/CMD 输入 `java -version`,能看到 17 字样即成功

### 2. 装 Android Studio
- 下载:<https://developer.android.com/studio>
- 一路下一步,到「Choose Components」勾上「Android Virtual Device」可不要(我们用真机)
- 首次启动会自动下载 Android SDK,选 SDK 34(Android 12.0 之上),Accept License
- 装完在 Android Studio → Settings → Languages & Frameworks → Android SDK:
  - SDK Platforms 勾 `Android 12.0 (API 31)` 和 `Android 14.0 (API 34)`
  - SDK Tools 勾 `Android SDK Build-Tools`、`Android SDK Platform-Tools`、`Android SDK Command-line Tools`

### 3. 配 SDK 环境变量
- 找到 SDK 安装路径(默认 `C:\Users\你的用户名\AppData\Local\Android\Sdk`)
- 系统变量新建:
  - `ANDROID_HOME` = SDK 路径
  - `ANDROID_SDK_ROOT` = 同上
  - `Path` 加 `%ANDROID_HOME%\platform-tools`(这样能用 adb)

## 二、鸿蒙4 测试机开 USB 调试

鸿蒙开调试模式跟安卓稍有不同:
1. 设置 → 关于手机 → 连点版本号 7 次(开发者选项开关)
2. 设置 → 系统和更新 → 开发人员选项
3. 打开「USB 调试」
4. **关键鸿蒙特有项**(影响录屏服务存活,必开):
   - 「仅充电模式下允许 ADB 调试」→ 开
   - 「后台活动限制」→ 把本 app 加入「不限制后台活动」白名单
   - 电池 → 启动管理 → 找到本 app → 关「自动管理」→ 手动开「允许自启动 / 允许关联启动 / 允许后台活动」三项
5. USB 连电脑,手机弹「允许 USB 调试吗」选确定

验证:PowerShell 跑 `adb devices`,能看到设备号 + `device` 即连上。

## 三、编译装包

### 方法 A:Android Studio(推荐,简单)
1. Android Studio → Open → 选 `D:\projects\ReplayRecoder`
2. 等 Gradle sync 完(首次会下依赖,几分钟)
3. 右上角设备下拉选你的鸿蒙机
4. 点绿色三角 Run,自动编译装到手机

### 方法 B:命令行(可选)
```bash
cd D:\projects\ReplayRecoder
.\gradlew.bat assembleDebug
# 生成 APK:app\build\outputs\apk\debug\app-debug.apk
adb install app\build\outputs\apk\debug\app-debug.apk
```

## 四、首次使用流程

1. 打开「录屏回放」app
2. 主界面默认选中「5min」,点「开始录制」
3. 系统弹窗请求屏幕录制 → 点「立即开始」
   - (若你开了「不再提示」也无所谓,授权一次有效)
4. 屏幕边缘出现红色悬浮小球 + 通知栏有「录制中」常驻通知
5. 正常操作扫地机 app / 测试,出 bug 瞬间:
   - 点悬浮小球,或
   - 下拉通知栏点「停止」,或
   - 双击音量键(需已开启无障碍服务)
6. 自动冻结最近 5 分钟成「回放事件」,停录
7. 主界面点「回放」(或相册点某事件) → ExoPlayer 串联播放该事件的分段视频
8. 回放页可填备注并点「保存备注」、点「导出 MP4」把段合并成单个 mp4 存到相册

## 五、常见问题(鸿蒙4 专项)

| 现象 | 原因 | 处理 |
|---|---|---|
| 录制几秒后自动停 | 后台被杀 | 启动管理关自动管理 + 开三项(见第二步) |
| 没有悬浮小球 | 悬浮窗权限未开 | 设置 → 权限 → 悬浮窗 → 本 app 开 |
| 通知栏没「录制中」通知 | 通知权限未开 | 设置 → 通知 → 本 app 允许 |
| 回放黑屏 | 分段编码/封装异常 | 看 logcat 过滤 `ScreenCapturer` `SegmentWriter` |
| 录音没声音(阶段3 前) | 阶段1 只录画面 | 正常,阶段3 接系统声 |

---

阶段1-6 全部实现并编译通过(2026-09-08)。下一步:装到鸿蒙4 测试机真机验证,重点测双击音量键拦截与后台保活。
