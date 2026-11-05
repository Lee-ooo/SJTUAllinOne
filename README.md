# SJTUAllinOne

面向上海交通大学学生的 Android 校园工具，将周课表、今日课程桌面组件、Canvas 作业、教室排课、考试与成绩集中到一个应用中。

## 功能

| 模块 | 功能 |
| --- | --- |
| 周课表 | 按学期保存，设置第一周周一；导入前预览确认；支持手动课程与本地修改、滑动切周、课程详情 |
| 今日课程组件 | 桌面查看今日课程，支持紧凑布局、字体、透明度、背景和“只看接下来”设置 |
| Canvas 作业 | 按截止时间或课程查看作业，同步作业状态并提供截止提醒 |
| 教室查询 | 按楼宇、日期和时间查看已知排课与教室信息 |
| 学业信息 | 查看教务考试安排和历史成绩 |
| 账号与同步 | 在学校 WebView 页面登录教务和 Canvas，提供本地缓存及同步状态 |

## 开始使用

1. 安装 APK，打开顶部“账号与同步”，分别完成教务和 Canvas 登录与验证。
2. 在课表右上角齿轮中设置学年、学期和第一周周一；点击“自动导入”，检查预览后保存，也可手动添加课程。
3. 在作业、学业页面刷新数据；教室页面先刷新目录，再选择楼宇、日期和时间查询。
4. 从系统桌面添加“今日课程”组件，并按需要调整样式与大小。

## 从源码构建

### 方案一：Android Studio（推荐，Windows / macOS / Linux）

1. 安装 [Git](https://git-scm.com/downloads) 和 [Android Studio](https://developer.android.com/studio)。选择支持 AGP 8.13.2 的 Android Studio 版本，首次启动完成 Setup Wizard，安装 Android SDK。
2. 克隆仓库：

   ```sh
   git clone https://github.com/Lee-ooo/SJTUAllinOne.git
   cd SJTUAllinOne
   ```

3. 在 Android Studio 中选择 **Open**，打开仓库根目录（包含 `settings.gradle.kts` 的目录，不是 `app/`）。
4. 打开 **Tools > SDK Manager**，在 **SDK Platforms** 中安装 **Android 16 / API 36**，在 **SDK Tools** 中安装 **Android SDK Build-Tools 35.0.0** 和 **Android SDK Platform-Tools**。接受许可，记录界面显示的 SDK Location；仅构建 APK 不需要模拟器、NDK 或 CMake。
5. 打开 **Settings > Build, Execution, Deployment > Build Tools > Gradle**（macOS 从 Android Studio 的设置进入），将 **Gradle JDK** 设为 **JDK 17**；若未安装，可在选择框中使用 **Download JDK** 下载 17。Gradle 使用项目 Wrapper 指定的 **8.13**，无需单独安装。项目还使用 Kotlin 2.2.20，保持仓库中的版本配置即可。
6. 确认项目根目录的 `local.properties` 指向本机 SDK。Android Studio 通常会自动创建；如提示 SDK 路径缺失，可手动创建，Windows 路径建议使用正斜杠：

   ```properties
   # 示例，请替换为 SDK Manager 显示的实际路径
   sdk.dir=C:/Users/yourname/AppData/Local/Android/Sdk
   ```

   macOS 常见路径为 `/Users/yourname/Library/Android/sdk`，Linux 常见路径为 `/home/yourname/Android/Sdk`。此文件属于本机配置，已被 Git 忽略。
7. 执行 **File > Sync Project with Gradle Files**，等待首次下载 Gradle 和依赖完成。需要能访问 Google Maven、Maven Central 和 Gradle 下载服务；若下载失败，在 Android Studio 的 HTTP Proxy 设置中配置自己的网络代理后重试。
8. 打开 **View > Tool Windows > Gradle**，点击 **Execute Gradle Task**，输入 `:app:assembleDebug` 并运行；也可在 `app > Tasks > build` 下双击 `assembleDebug`。看到 `BUILD SUCCESSFUL` 后即可取出 APK，无需连接手机。

> 本仓库的 Windows `gradlew.bat` 委托给 `scripts/android.ps1`，依赖未随 Git 上传的 `.local/` 工具。全新克隆时请使用上述 IDE 的 Gradle 任务入口；在 IDE 的 Terminal 中运行 `gradlew.bat` 仍会触发这项本地依赖。

### 方案二：VS Code / 终端

VS Code 可作为编辑器，构建仍需 JDK 和 Android SDK。安装 **JDK 17**，将 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 加入 `PATH`；使用 `java -version` 检查版本。SDK 可通过 Android Studio 的 SDK Manager 安装，也可从 [Android Studio 下载页](https://developer.android.com/studio#command-line-tools-only) 下载 Command-line Tools，解压为 `<SDK>/cmdline-tools/latest/`，确保其中存在 `bin/sdkmanager`（Windows 为 `sdkmanager.bat`）。

使用 Command-line Tools 时，在该工具的 `bin` 目录打开终端，安装所需组件并接受许可（将 `<SDK>` 替换为 SDK 的绝对路径）：

```sh
# macOS / Linux
./sdkmanager --sdk_root="<SDK>" "platforms;android-36" "build-tools;35.0.0" "platform-tools"
./sdkmanager --sdk_root="<SDK>" --licenses
```

```powershell
# Windows PowerShell
.\sdkmanager.bat --sdk_root="<SDK>" "platforms;android-36" "build-tools;35.0.0" "platform-tools"
.\sdkmanager.bat --sdk_root="<SDK>" --licenses
```

按方案一克隆仓库并创建 `local.properties`，随后在仓库根目录构建：

```sh
# macOS / Linux：Wrapper 自动下载 Gradle 8.13
sh ./gradlew :app:assembleDebug
```

```powershell
# Windows：另行下载 Gradle 8.13 的 binary-only ZIP，解压并将 bin 加入 PATH
# 下载地址：https://services.gradle.org/distributions/gradle-8.13-bin.zip
# 使用全局 gradle 命令避开本仓库定制的 gradlew.bat
gradle --version
gradle :app:assembleDebug
```

可选质量检查：在同一构建入口执行 `:app:testDebugUnitTest :app:lintDebug`。只生成 APK 时无需先运行这些任务。

### 获取与安装 APK

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `dev.sjtu.allinone.debug`，已自动使用调试密钥签名，可安装到 **Android 8.0（API 26）及以上**设备。可以将文件传到手机并允许从该来源安装，或打开 USB 调试、连接并授权电脑后，用 SDK Platform-Tools 中的 `adb` 安装：

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

使用 `adb` 前将 `<SDK>/platform-tools` 加入 `PATH`，或使用其完整路径。需要正式发布包时，在 Android Studio 中使用 **Build > Generate Signed Bundle / APK**，选择 **APK**、自己的签名密钥和 `release`；当前项目未配置发布签名，单独运行 `assembleRelease` 不会得到已签名的发布 APK。

构建与签名操作可参考 [Android 官方构建说明](https://developer.android.com/build/build-for-release)，JDK 设置见 [Android 构建中的 Java 版本](https://developer.android.com/build/jdks)。

## 验证与开发

JVM 单元测试位于 `app/src/test/`，设备 UI 与集成测试位于 `app/src/androidTest/`。连接设备或模拟器后可运行 `:app:connectedDebugAndroidTest`；部分真实会话探针需已有登录状态，详见测试源码。完整模拟器检查入口为 `scripts/verify-emulator.ps1`，依赖本地工具与指定模拟器环境。

```text
app/src/main/java/dev/sjtu/allinone/
├── auth/          # WebView 登录与会话入口
├── background/    # 后台同步与提醒
├── data/          # Room、本地设置、远端接口与仓库
├── domain/        # 课程、作业及组件业务规则
├── ui/            # Compose 页面
└── widget/        # 今日课程桌面组件
```

## 数据与隐私

教务数据来自 `j.sjtu.edu.cn`，作业来自学校 Canvas `oc.sjtu.edu.cn`，教室排课来自 `ids.sjtu.edu.cn`。

登录在学校页面中完成；会话 Cookie 使用 Android Keystore 保护的密钥加密保存，教务和 Canvas 会话分别管理。上传问题反馈时请去除学号、成绩、课表等个人信息，以及 Cookie、令牌和登录截图。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/Lee-ooo/SJTUAllinOne/issues) 报告问题，附上 Android 版本、复现步骤和脱敏日志；代码改动可提交 Pull Request。

## 许可证

Copyright (c) 2026 Lee-ooo

本项目原创代码采用 [GNU General Public License v3.0](LICENSE)（SPDX：`GPL-3.0-only`）。你可以依照该许可证使用、修改和分发；分发修改版或二进制时，须遵守 GPL-3.0 的源码提供及许可证要求。本软件不提供任何担保，详见许可证全文。

第三方依赖及工具文件仍遵循各自的许可证，原有版权声明予以保留。
