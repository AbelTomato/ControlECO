# 番茄记账应用身份与新发行线设计

## 目标

沿用当前 ControlECO Android 工程、模块边界、数据库和全部功能，建立独立的「番茄记账」新应用身份与自有发行线。此变更不重做产品功能，也不迁移旧安装中的本地数据。

## 已确认决策

- 中文产品名：番茄记账。
- 英文产品名：Tomato Ledger。
- 新 Android `applicationId`：`com.abeltomato.tomatoledger`。
- Kotlin `namespace` 与源码包路径继续使用 `com.controleco.app`，不做全仓包名迁移。
- 新发行线从 `versionName = "1.0.0"`、`versionCode = 1` 开始。
- 新 applicationId 被 Android 视作另一款应用：旧版可并存，不能覆盖安装；旧版本地数据库、设置和权限不自动迁移。
- 继续沿用现有账本 JSON 备份/恢复功能作为用户手动转移账本的途径；排除数据仍按备份功能既有范围处理。
- 自有仓库为 `AbelTomato/ControlECO`；应用内更新检查、仓库链接及 CI Release 保持指向该仓库。
- Release APK 从新发行线首发起使用同一长期 release key；不能再使用旧版 debug key，也不能后续轮换签名而不提供迁移方案。
- 保留当前所有记账、捕获、分类建议、备份与恢复功能。

## 身份边界

`applicationId` 是 Android 安装身份，不等同于 Kotlin `namespace`。仅更换 `applicationId` 可让新旧 APK 并存，同时保留现有源码包名、生成 `R`/`BuildConfig` 的 namespace、Room 数据库实现和模块组织。Manifest 中通过 `${applicationId}` 生成的 FileProvider authority 会随新 ID 自动变化。

必须检查并更新所有硬编码旧包名的 Android 组件引用，尤其是 `app/src/main/res/xml/shortcuts.xml` 中快捷方式的 target package/class。类名仍使用原 Kotlin 包名。其他基于应用自身 package name 的 URI、权限或安装流程应由 `${applicationId}` / `context.packageName` 动态生成，不应继续依赖旧 applicationId。

## 显示名称

- `values/strings.xml` 中 launcher app name 改为「番茄记账」。
- `values-en/strings.xml` 中 launcher app name 改为 `Tomato Ledger`。
- 无障碍服务名称同步使用「番茄记账支付页面采集」及英文等义表述。
- 只修改面向用户、且当前明确显示旧品牌的标题、帮助语句和 README 品牌介绍；内部包、日志 tag、历史文档与数据 schema 不为品牌改名而批量重写。

## 版本与更新

`versionName` 用于用户展示和更新提示，允许新应用从 `1.0.0` 开始。`versionCode` 在新 applicationId 的独立安装序列从 1 起步，并在该发行线的每次发布中严格递增。自有 updater 继续读取 `AbelTomato/ControlECO` Releases，并使用既有资产命名约定 `ControlECO-<tag>.apk`，避免扩大本次更新器协议改动；tag 使用 `v1.0.0` 格式，资产名使用 tag 的完整值。

正式触发首个新应用 Release 前，CI 必须具备发布仓库的长期签名 Secrets。发布工作流必须构建并测试新 applicationId 的 APK，资产上传到自有 Release。若新仓库尚无旧格式兼容要求，可沿用已有工作流协议与 release notes 生成方式。

## 数据与迁移

新 applicationId 有独立的 Android 私有存储空间，既有安装数据不会被新应用访问。首发版本不实现跨包自动数据库迁移、ContentProvider 导出或静默复制。用户可在旧应用导出 JSON 账本备份，再在番茄记账中通过文件选择器恢复。用户需手动重授通知访问、无障碍、悬浮窗、电池优化等权限；备份不包含的设置、待确认项目、捕获日志与溯源数据不会转移。

## 实施范围

实施阶段应修改：

- `app/build.gradle.kts`：applicationId、版本名和版本码。
- `app/src/main/res/values/strings.xml` 与 `values-en/strings.xml`：中英文产品显示名和无障碍服务标签。
- `app/src/main/res/xml/shortcuts.xml`：Android shortcut 的 target package，保留类名 namespace。
- Manifest、FileProvider、通知/Intent 和测试：仅修复发现的硬编码旧 applicationId 引用。
- `README.md`、`.github/workflows/release.yml` 与更新器测试：说明新应用身份、版本起点、Release tag/资产兼容约定和备份迁移限制。
- 必要的构建/资源/更新器测试。

不在范围内：Kotlin package 全量重命名、Room schema/数据库重构、功能删减或重做、旧应用数据自动迁移、替换 GitHub 仓库、创建真实 Release、生成或提交签名密钥。

## 验收标准

1. Debug 与签名 Release manifest 的 applicationId 均为 `com.abeltomato.tomatoledger`，Kotlin namespace 仍为 `com.controleco.app`。
2. 中文系统显示名为「番茄记账」，英文资源显示名为 `Tomato Ledger`；无障碍服务及快捷方式不再显示旧产品名/指向旧 applicationId。
3. 新 applicationId 的 `versionName` 为 `1.0.0`、`versionCode` 为 `1`；后续发行规则要求 versionCode 递增。
4. 应用内更新器仍查询 `AbelTomato/ControlECO` Releases，且 APK 资产约定与 CI 上传名称一致。
5. 同一设备上新旧 applicationId 可分别安装；文档准确说明数据和权限不会自动继承，手动 JSON 备份/恢复是支持的账本迁移路径。
6. 相关单元测试、Debug 编译和带签名配置的 Release 构建通过；Release APK 签名证书与 configured long-lived key 一致。
7. 未创建或发布实际 GitHub Release，未提交任何 keystore、密码或真实训练数据。