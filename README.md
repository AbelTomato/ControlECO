# 番茄记账（Tomato Ledger）

安卓记账应用，目标是逐步实现"自动记账"：自动捕获微信/支付宝/云闪付通知、电商收银台、银行动账短信与银行 App 推送、
支付页文字（无障碍）等交易事件；底部悬浮确认卡与待确认队列双通道确认；商户归类越用越准。
数据全部保存在本机；唯一联网用途是更新检查与更新包下载（只访问 GitHub，不上传任何数据）。

## 开发环境

- JDK 17：`D:\Java\jdk-17.0.20.1+1`（Temurin，未写入系统环境变量，构建时临时注入）
- Android SDK：`D:\AndroidSDK`（cmdline-tools + platform-tools + platforms;android-35 + build-tools;35.0.0）
- Gradle 8.10.2：`D:\gradle\gradle-8.10.2`（项目自带 wrapper，依赖走阿里云镜像，见 `settings.gradle.kts`）

## 构建

```bash
# Git Bash 下
export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'
export PATH="/d/Java/jdk-17.0.20.1+1/bin:$PATH"
"D:/gradle/gradle-8.10.2/bin/gradle.bat" :app:assembleDebug
# 或使用 wrapper（首次会下载 Gradle 发行版，distributionUrl 已指向腾讯镜像）
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 自有 Release 发布

番茄记账使用独立 Android 应用身份 `com.abeltomato.tomatoledger`，版本序列从 `1.0.0`（`versionCode=1`）开始；Kotlin namespace 仍为 `com.controleco.app`。后续每次发布必须递增 `versionCode`。新旧应用可以并存，但不能覆盖安装；旧应用的本地数据和权限不会自动迁移。需要转移账本时，请在旧应用导出 JSON 账本备份，再在番茄记账中恢复，并重新授予通知访问、无障碍、悬浮窗等所需权限。备份不包含的设置和捕获状态不会转移。

GitHub Actions 工作流 `.github/workflows/release.yml` 在推送 `v*` 标签或手动触发时运行测试、构建签名 Release APK，并创建同名 GitHub Release。首发标签格式为 `v1.0.0`，APK 资产名为 `ControlECO-<tag>.apk`（首发为 `ControlECO-v1.0.0.apk`），供应用内更新器下载。应用内更新检查和个人页仓库入口指向 `AbelTomato/ControlECO`。

发布前，在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 配置以下 Secrets：

- `RELEASE_KEYSTORE_BASE64`：release keystore 文件的 Base64 内容
- `RELEASE_STORE_PASSWORD`：keystore 密码
- `RELEASE_KEY_ALIAS`：签名 key alias
- `RELEASE_KEY_PASSWORD`：签名 key 密码

本地构建可在仓库根目录创建未跟踪的 `keystore.properties`，填写 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。该文件和 `*.jks`、`*.keystore` 已加入 `.gitignore`。没有完整的 release 签名配置时，Release 构建会失败，不会退回 debug key。

签名密钥必须长期、离线备份；所有后续更新都要用同一 keystore/key 签名，否则 Android 无法覆盖安装，用户需卸载后重装且本地数据会丢失。密钥不应提交到 Git 或发到聊天中。密钥生成步骤见下文“生成 Release 签名密钥”。

### 生成 Release 签名密钥

在可信的本机终端运行（需要 JDK 17 的 `keytool`），按提示输入并确认密码。请将路径替换为安全的备份目录；不要把 keystore 放入仓库：

```bash
keytool -genkeypair -v \
  -keystore "$HOME/controleco-release.jks" \
  -alias controleco-release \
  -keyalg RSA -keysize 3072 -validity 10000
```

记下 alias（示例为 `controleco-release`）及输入的 keystore/key 密码。安全备份 `.jks` 文件和密码，最好有离线副本；丢失后无法为已安装版本签发可覆盖更新的 APK。

为 GitHub Actions 配置 `RELEASE_KEYSTORE_BASE64` 时，在本机编码整个文件，再将命令输出粘贴进 Secret：

```bash
base64 -w 0 "$HOME/controleco-release.jks"
```

GitHub Secrets 中的 `RELEASE_STORE_PASSWORD` 和 `RELEASE_KEY_PASSWORD` 填入生成时对应的密码，`RELEASE_KEY_ALIAS` 填 `controleco-release`。若生成时 keystore 密码和 key 密码相同，两项填相同值。

首次发布前，确认版本号已更新且签名 Secrets 已配置，然后推送标签（示例）：

```bash
git tag v0.5.6-beta
git push origin v0.5.6-beta
```

## 真机调试（USB 实时日志）

1. **手机打开开发者选项**：设置 → 关于手机 → 连点"版本号"7 次；华为/荣耀通常是 设置 → 系统和更新 → 开发人员选项（或先在关于手机连点版本号开启）。
2. **打开 USB 调试**：开发者选项里开启"USB 调试"；华为/荣耀若还有"仅充电模式下允许 ADB 调试"，一并开启。
3. **连接**：数据线连接电脑，手机弹窗"允许 USB 调试？"勾选"一律允许"再确定。
4. **电脑端（Git Bash）**：

```bash
ADB="D:/AndroidSDK/platform-tools/adb.exe"
"$ADB" devices                 # 确认设备已连接（状态 device；显示 unauthorized 要回手机重新授权）
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk   # 装机
"$ADB" logcat -c               # 清空旧日志
"$ADB" logcat -v time -s ControlECO          # 实时日志：本应用全部（[Ntf] 通知 / [Page] 页面 / [Sms] 短信 / [Overlay] 悬浮卡 / [learn] 归类学习）
"$ADB" logcat -v time -s ControlECO | grep Page    # 只看页面通道（页面快照/规则判定/悬浮窗原因）
"$ADB" logcat -v time -s ControlECO > controleco.log  # 边看边存文件，方便把样本发给开发会话
```

5. **采样流程**（页面规则调试）：`logcat -c` 清屏 → 手机上完整走一遍支付 → 终端里 `snapshot pkg=... texts=[...]` 就是页面文本快照，复制出来对照修改 `PageRules.kt`（流程见维护指南第五节）。

