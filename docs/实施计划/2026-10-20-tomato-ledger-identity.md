# 番茄记账应用身份与发行线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留当前 Android 工程结构和全部功能，将新发行线建立为「番茄记账 / Tomato Ledger」，使用独立 applicationId 和从 1.0.0 开始的版本序列。

**Architecture:** 只修改 Android 安装身份、用户可见品牌、发行线版本及其发布/更新引用。保留 Kotlin namespace `com.controleco.app`、源码目录、数据库 schema 和既有功能；新 applicationId 的私有数据不与旧应用共享，账本通过现有 JSON 备份手动迁移。

**Tech Stack:** Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Android resources/Manifest、JUnit 4、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-20-tomato-ledger-identity-design.md`

## Global Constraints

- 中文产品名为「番茄记账」，英文产品名为 `Tomato Ledger`。
- 新 Android `applicationId` 固定为 `com.abeltomato.tomatoledger`。
- Kotlin `namespace` 与源码包路径继续为 `com.controleco.app`，不做全仓包迁移。
- 新发行线从 `versionName = "1.0.0"`、`versionCode = 1` 开始；该发行线以后每次发布都必须提高 `versionCode`。
- 新旧 applicationId 可并存；本计划不实现旧版数据自动迁移。账本手动迁移只使用既有 JSON 备份/恢复能力。
- updater、仓库链接和 CI Release 继续使用 `AbelTomato/ControlECO`；资产名保持 `ControlECO-<tag>.apk`，tag 使用 `v1.0.0` 形式。
- Release 使用配置的长期 release key；不生成、提交或输出任何密钥，不创建或发布真实 GitHub Release。
- 保留当前所有记账、捕获、分类建议、备份和恢复功能；不改 Room schema。

---

## File Structure

- `app/build.gradle.kts`：新 applicationId 与版本起点；保持 `namespace` 不变。
- `app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`：中英文应用名和无障碍服务名称。
- `app/src/main/res/xml/shortcuts.xml`：快捷方式目标包名，组件类名仍指向现有 Kotlin namespace。
- `app/src/main/AndroidManifest.xml` 及 Android 组件源码：仅在发现硬编码旧 applicationId 时改为新 ID 或动态 package API。
- `app/src/main/java/com/controleco/app/update/AppUpdate.kt`、`app/src/test/java/com/controleco/app/update/AppUpdateTest.kt`：保持自有仓库与 Release APK 资产协议，验证新发行 tag/asset URL 解析行为。
- `app/src/main/java/com/controleco/app/ui/profile/ProfileScreen.kt`、`README.md`、`.github/workflows/release.yml`：对齐新品牌、版本和备份迁移说明；CI 上传协议不变。
- `app/src/test/`：补充或调整包身份/更新协议的可自动验证项；Manifest 与 APK 元数据以 Gradle 构建产物检查为主。

## Task 1: 建立 Android 新应用身份和版本序列

**Files:**
- Modify: `app/build.gradle.kts`
- Test: Gradle-generated debug manifest/package metadata

**Interfaces:**
- Produces Android `applicationId = "com.abeltomato.tomatoledger"`。
- Retains `namespace = "com.controleco.app"`。
- Produces `versionName = "1.0.0"` and `versionCode = 1`。
- [x] **Step 1: 更新 applicationId 和版本常量**，不改 namespace、minSdk、targetSdk 或依赖。
- [x] **Step 2: 构建 Debug variant**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew --no-daemon :app:processDebugManifest :app:assembleDebug`。
- [x] **Step 3: 检查生成 APK/manifest 元数据**：确认 package 为 `com.abeltomato.tomatoledger`，versionName 为 `1.0.0`，versionCode 为 `1`，同时源码 namespace 仍为 `com.controleco.app`。
- [x] **Step 4: 搜索旧 applicationId 的硬编码 Android 组件引用**：`rg -n 'com\\.controleco\\.app' app/src/main/res app/src/main/AndroidManifest.xml`；逐项判断是源码类名（保留）还是应用身份（应修改）。`targetPackage` 已改为新 applicationId；`targetClass` 保留 namespace 类名。

## Task 2: 更新中英文品牌和 Android 快捷方式

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/res/xml/shortcuts.xml`
- Test: `:app:processDebugResources` 与 APK 构建

**Interfaces:**
- Chinese launcher/service labels use 「番茄记账」。
- English launcher label uses `Tomato Ledger`；服务标签用准确英文表述支付页面采集用途。
- Shortcut target package is `com.abeltomato.tomatoledger`；target class remains under `com.controleco.app`。

- [x] **Step 1: 修改默认及英文 `app_name` 和无障碍服务标签**，不批量替换历史说明或内部日志 tag。
- [x] **Step 2: 修改快捷方式 `targetPackage`**，保留完整 `targetClass` 的现有源码类路径；检查通知、FileProvider 和应用内 Intent 是否动态读取 `${applicationId}`/`context.packageName`。快捷方式原本已是新包名；Manifest 中 FileProvider authority 使用 `${applicationId}`。
- [x] **Step 3: 构建资源和 APK**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew --no-daemon :app:processDebugResources :app:assembleDebug`。
- [x] **Step 4: 检查打包资源与 Manifest 标签**，确认中文默认资源、英文资源、无障碍服务名、launcher label 和 shortcut package 均符合新身份；`aapt dump badging` 确认 APK 应用名及英文/中文标签，打包资源确认无障碍服务标签和三个快捷方式目标。

## Task 3: 对齐应用内更新协议和 Release CI

**Files:**
- Review/Modify: `app/src/main/java/com/controleco/app/update/AppUpdate.kt`
- Modify/Test: `app/src/test/java/com/controleco/app/update/AppUpdateTest.kt`
- Review/Modify: `app/src/main/java/com/controleco/app/ui/profile/ProfileScreen.kt`
- Review/Modify: `.github/workflows/release.yml`
- Modify: `README.md`

**Interfaces:**
- Release 查询 owner/repo 为 `AbelTomato/ControlECO`。
- updater 接受的 APK 名称继续为 `ControlECO-<tag>.apk`；首发 tag 例如 `v1.0.0`，上传资产示例 `ControlECO-v1.0.0.apk`。
- Release CI 使用已配置的长期签名 Secrets，运行测试并构建新 applicationId 的 Release APK；本计划不创建 Release。

- [x] **Step 1: 更新/补充 updater 单测**：覆盖 `v1.0.0` 资产解析、无 APK 时返回空下载地址以及版本比较 `1.0.0` 到 `1.0.1`；断言 URL 指向自有仓库和约定资产名。
- [x] **Step 2: 运行 updater 目标测试并观察预期结果**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew --no-daemon :app:testDebugUnitTest --tests '*AppUpdateTest'`，通过。
- [x] **Step 3: 对齐仓库入口与 CI 资产协议**：仓库入口指向自有仓库；工作流只接受 `v*` tag 触发，构建输出复制为 `ControlECO-${tag}.apk` 后上传；核对现有配置已符合约定。
- [x] **Step 4: 更新 README**，准确写明番茄记账品牌、1.0.0 起始发行线、release signing secrets、标签发布方式、旧应用与新应用不能覆盖安装，以及通过账本 JSON 手动迁移数据和需重新授予权限。
- [x] **Step 5: 校验工作流 YAML 和变更 whitespace**：使用 Python PyYAML 解析 `.github/workflows/release.yml` 成功；`git diff --check` 通过。

## Task 4: 完整验证与发布前检查

**Files:**
- Verify: 上述所有改动文件及 `docs/superpowers/specs/2026-10-20-tomato-ledger-identity-design.md`

**Interfaces:**
- 不改 Room schema，不产生旧数据自动迁移行为。
- 本机签名缺失时 Release 构建必须失败；配置真实长期 key 后才验证签名 Release。

- [ ] **Step 1: 运行全部 JVM 单元测试和 Debug 编译**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugKotlin`。
- [ ] **Step 2: 验证 Release 签名配置保护**：未配置 keystore 时运行 `ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew --no-daemon :app:validateSigningRelease`，确认明确报缺少签名配置；不得回退 debug key。
- [ ] **Step 3: 在用户配置 GitHub Secrets 后运行签名 Release 构建**：GitHub Actions 构建 `:app:testDebugUnitTest :app:assembleRelease`，检查 applicationId/version metadata、APK 文件及签名证书 SHA-256 与长期 key 相符。
- [ ] **Step 4: 检查最终变更**：确认没有旧 applicationId 硬编码作为 Android 安装身份，没有 keystore/密码/训练数据进入版本控制；确认未创建真实 GitHub Release，也未推送标签。

## Risks and Stop Conditions

- 更改 `applicationId` 意味着旧版数据和权限不会被新应用继承；不得声称可原位升级或自动迁移。
- 若任意 Android 组件仍将 `com.controleco.app` 当作 applicationId 使用，必须在构建/打包验证中定位并改为新 ID 或动态引用；源码类名属于 namespace 时不应改名。
- 首个签名 APK 必须使用用户建立的长期 key。若用户尚未配置 GitHub Secrets，仅完成本地测试与无签名配置保护验证，不生成替代密钥、不发布 Release。
- CI 和 updater 的 APK 资产名必须完全一致，否则客户端无法取得安装包。
- 用户要求沿用框架，因此不做目录搬迁、全仓品牌文字机械替换或无关 UI 重构。