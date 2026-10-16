# 本地账本备份与恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 应用内提供版本化核心账本备份文件的导出与恢复，以便签名更换、卸载重装或换机后恢复数据。

**Architecture:** 使用纯 Kotlin 的版本化 JSON DTO 表示账户、分类、交易、商户学习规则及自定义分类图片附件。Room 快照与恢复由数据层协调；恢复先校验和确认，再通过单个 Room transaction 整体替换核心表，并以临时文件/补偿清理处理图片附件。设置页通过 Storage Access Framework 的 CreateDocument/OpenDocument 选择目标文件，不申请广泛存储权限。

**Tech Stack:** Kotlin、Android Storage Access Framework、Android `org.json`、Room 2、Coroutines、现有 JUnit 4。

**Spec:** 本计划实现用户在会话中确认的本地核心账本备份设计；仓库暂未建立独立 spec 文件。

## Global Constraints

- 备份范围仅为账户、分类、交易、商户学习规则及被分类引用的自定义图片；不包含待确认条目、捕获日志、溯源记录、设置偏好及分类月预算。
- 恢复采用确认后的整体替换，不合并；输入无效、校验失败或数据库写入失败时不得留下部分恢复状态。
- 不要求旧/新 APK 使用相同签名；备份文件必须能由已安装应用通过系统文件选择器写出和读入。
- 不新增网络调用、广泛存储权限或第三方运行时依赖。
- 备份包含财务隐私数据；界面必须提示用户安全保存，并且应用日志不得输出交易明细或完整备份内容。
- 保留当前 Room schema version 8，不改变实体字段或数据库迁移。
- 实施分支为 `feat/ledger-backup-restore`；不在本计划中提交或推送分支。

---

## File Structure

- `app/src/main/java/com/controleco/app/data/backup/LedgerBackup.kt`：备份 DTO、JSON 编解码、格式版本和输入校验；不依赖 Android Context 或 Room。
- `app/src/main/java/com/controleco/app/data/backup/LedgerBackupRepository.kt`：从 DAO 建立备份快照、验证数据库替换输入、协调图片附件与数据库事务。
- `app/src/main/java/com/controleco/app/data/db/Daos.kt`：增加核心表所需的全量替换 DAO 方法（空表、显式 ID 批量插入）。
- `app/src/main/java/com/controleco/app/data/db/AccBookDatabase.kt`：提供跨 DAO 原子恢复入口，或提供 `withTransaction` 调用所需的数据库实例能力；Room 版本不变。
- `app/src/main/java/com/controleco/app/data/CategoryIcon.kt`：仅当有必要时添加受限文件名/图片附件安全 helper；沿用 `cat_icon_` 文件命名规则。
- `app/src/main/java/com/controleco/app/data/LedgerViewModel.kt`：在 IO dispatcher 协调备份读取/写入状态和恢复确认结果。
- `app/src/main/java/com/controleco/app/ui/settings/SettingsImportScreen.kt`：增加“备份与恢复”区、文件选择器入口、替换确认和结果/错误显示。
- `app/src/main/java/com/controleco/app/ui/AppNav.kt`：把文件选择器结果转交 ViewModel/屏幕回调；避免把文件内容编码进导航 route。
- `app/src/test/java/com/controleco/app/data/backup/LedgerBackupTest.kt`：纯 JVM JSON schema、往返和校验测试。
- `app/src/test/java/com/controleco/app/data/backup/LedgerBackupRepositoryTest.kt`：恢复顺序/事务回滚/附件失败测试；使用项目现有可用的 Room 测试设施，如无设施则抽离可纯 JVM 测试的协调逻辑并标明集成验证。
- `app/src/test/java/com/controleco/app/ui/settings/SettingsImportScreenTest.kt`：若项目当前有 Compose UI 测试依赖则验证关键交互；否则用 ViewModel 状态测试覆盖确认、取消与失败路径。
- `docs/自动记账捕获机制.md`：补充备份范围、排除项、隐私提示与恢复替换语义。

---

### Task 1: 定义并测试版本化备份格式

**Files:**
- Create: `app/src/main/java/com/controleco/app/data/backup/LedgerBackup.kt`
- Create: `app/src/test/java/com/controleco/app/data/backup/LedgerBackupTest.kt`

**Interfaces:**
- `data class LedgerBackup(val formatVersion: Int, val exportedAt: Long, val accounts: List<AccountEntity>, val categories: List<CategoryEntity>, val transactions: List<TransactionEntity>, val merchantRules: List<MerchantRuleEntity>, val imageFiles: Map<String, String>)`。`imageFiles` 的 value 为图片原始字节的 Base64 字符串，key 为应用生成的 `cat_icon_*.jpg` 文件名。
- `object LedgerBackupCodec { fun encode(backup: LedgerBackup): String; fun decode(json: String): LedgerBackup }`。
- `object LedgerBackupValidator { fun validate(backup: LedgerBackup): List<String> }`；空列表表示合法，否则返回不含用户数据的字段级错误。
- 当前 `formatVersion` 固定为 `1`。未知版本拒绝恢复，不做静默兼容猜测。

- [x] **Step 1: 编写失败测试**，覆盖 v1 完整数据 JSON 往返（含退款字段、来源、空 externalId、自定义 icon 与图片附件）、未知版本、缺必需字段、非法枚举、重复账户/分类/交易 ID、重复非空 `externalId`、交易引用缺失账户/分类、商户规则引用缺失分类/账户、规则 `(matchKey,type)` 重复、图片文件名越权路径、缺失/无引用图片附件、Base64 损坏和超大输入拒绝。
- [x] **Step 2: 运行目标测试确认失败**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackupTest'`。首次测试按预期因 DTO/codec 尚不存在而编译失败。
- [x] **Step 3: 实现纯 Kotlin DTO、JSON codec 和 validator**，对字符串/列表/附件总字节数设明确上限；校验不记录具体交易值；所有实体字段显式编码，避免依赖反射或序列化插件。
- [x] **Step 4: 重跑目标测试**，确认合法 fixture 往返保持所有字段，非法文件均在任何数据库变更前拒绝。结果：目标测试通过；JSON 输入上限 4 MiB、字符串 256 KiB、列表 100,000 项、附件解码总量 16 MiB。
- [x] **Step 5: 提交该格式切片**：`git add app/src/main/java/com/controleco/app/data/backup/LedgerBackup.kt app/src/test/java/com/controleco/app/data/backup/LedgerBackupTest.kt && git commit -m "feat: define versioned ledger backup format"`。

### Task 2: 建立核心账本快照和原子恢复数据层

**Files:**
- Create: `app/src/main/java/com/controleco/app/data/backup/LedgerBackupRepository.kt`
- Modify: `app/src/main/java/com/controleco/app/data/db/Daos.kt`
- Modify: `app/src/main/java/com/controleco/app/data/db/AccBookDatabase.kt`
- Modify: `app/src/main/java/com/controleco/app/data/CategoryIcon.kt` only if image file validation helper is needed
- Create: `app/src/test/java/com/controleco/app/data/backup/LedgerBackupRepositoryTest.kt`

**Interfaces:**
- `suspend fun createBackup(context: Context, db: AccBookDatabase, exportedAt: Long): LedgerBackup`；只读取账户、分类、交易、商户学习规则及这些分类引用的图片。
- `suspend fun restoreBackup(context: Context, db: AccBookDatabase, backup: LedgerBackup)`；要求调用前备份已经通过 `LedgerBackupValidator.validate`。
- DAO 批量插入必须保留备份实体主键，不能把导入数据重新映射成新 ID。
- 恢复删除顺序为 transactions → merchant_rules → categories → accounts；插入顺序为 accounts → categories → transactions → merchant_rules。

- [ ] **Step 1: 为快照投影和 DAO 替换 API 编写测试**，确认主键、退款标记、图标规格和 merchant rules 原样保留，且不读取 `captured_notifications`、`capture_traces`、`pending_transactions`。
- [ ] **Step 2: 运行目标测试确认失败**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackupRepositoryTest'`。
- [ ] **Step 3: 增加核心表 DAO 全量读取/删除/显式 ID 批量插入方法**；通过 `RoomDatabase.withTransaction` 在单个事务中执行删除及按 FK 顺序插入。不得调用数据库 `clearAllTables()`，因为范围外捕获与溯源数据必须保留。
- [ ] **Step 4: 实现图片附件快照/恢复**：只允许 `CategoryIcon.imageOf(icon)` 得到且匹配 `cat_icon_[A-Za-z0-9_-]+\\.jpg` 的 basename；拒绝路径分隔符和目录遍历。恢复图片先写到临时文件并校验 JPEG 解码，再原子重命名；若 DB 事务失败，清理本次新文件且保持旧数据可用。对替换时旧文件的清理必须等数据库提交成功后执行。
- [ ] **Step 5: 验证恢复失败原子性与范围隔离**：制造唯一键/外键冲突，在事务抛错后断言旧核心数据仍在且待确认/捕获/溯源表内容不变；附件写入失败时断言数据库未开始替换。若现有测试环境不能直接创建 Room in-memory DB，抽出事务调用边界并增加实际 Android instrumentation/manual verification，不得把未覆盖情况写成已测试。
- [ ] **Step 6: 运行目标测试和 Kotlin 编译**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackupRepositoryTest' :app:compileDebugKotlin`。
- [ ] **Step 7: 提交数据层切片**：`git add app/src/main/java/com/controleco/app/data/backup app/src/main/java/com/controleco/app/data/db/Daos.kt app/src/main/java/com/controleco/app/data/db/AccBookDatabase.kt app/src/main/java/com/controleco/app/data/CategoryIcon.kt app/src/test/java/com/controleco/app/data/backup/LedgerBackupRepositoryTest.kt && git commit -m "feat: add atomic ledger backup restore repository"`。

### Task 3: 接入文件选择器、ViewModel 状态与恢复确认

**Files:**
- Modify: `app/src/main/java/com/controleco/app/data/LedgerViewModel.kt`
- Modify: `app/src/main/java/com/controleco/app/ui/settings/SettingsImportScreen.kt`
- Modify: `app/src/main/java/com/controleco/app/ui/AppNav.kt`
- Create or modify: ViewModel test under `app/src/test/java/com/controleco/app/data/` following current repository test conventions

**Interfaces:**
- ViewModel 暴露只读 backup/restore 状态（idle、processing、success/error）及 `exportBackup(uri: Uri)`、`readBackup(uri: Uri)`、`confirmRestore()`、`cancelRestore()`。
- 文件 URI 由 Storage Access Framework 提供；读取/写入和图片打包均在 `Dispatchers.IO` 执行。
- `readBackup` 只解析并校验，形成待确认的摘要；只有 `confirmRestore` 能调用数据层恢复。
- 恢复摘要至少显示备份时间以及账户、分类、交易、商户规则数量；提示会整体替换对应现有数据。

- [ ] **Step 1: 添加 ViewModel 状态测试**，证明取消不会调用 repository restore、校验失败不显示成功、确认后才发起整体恢复、导出错误可见且不包含交易明细。
- [ ] **Step 2: 运行目标测试确认失败**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackupViewModelTest'`。
- [ ] **Step 3: 实现 ViewModel 文件流**：使用 `contentResolver.openInputStream/openOutputStream`；拒绝空 URI/空流；设置合理最大备份读取字节数；文件写入先编码完整内容后写入，关闭流后回报成功；异常转为通用且可操作的错误状态，不写用户数据日志。
- [ ] **Step 4: 在现有账单导入设置页添加独立“本地账本备份”区**，说明备份范围、排除范围及文件含财务隐私；增加“导出备份”和“恢复备份”操作。CreateDocument MIME 使用 `application/json`，建议文件名 `controleco-ledger-backup-v1.json`；OpenDocument 限定 JSON/通用文件类型，不要求存储权限。
- [ ] **Step 5: 实现恢复二次确认 UI**：显示导出时间与四类记录数量，明确“将替换当前账户、分类、交易和商户学习规则”；取消只清除待确认备份；确认后禁用重复提交并显示进度/成功/失败。
- [ ] **Step 6: 运行 ViewModel/UI 目标测试和 Kotlin 编译**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackupViewModelTest' :app:compileDebugKotlin`。若有 Compose UI 测试依赖，再加目标页面测试；不为此引入新的测试依赖。
- [ ] **Step 7: 提交 UI 流程切片**：`git add app/src/main/java/com/controleco/app/data/LedgerViewModel.kt app/src/main/java/com/controleco/app/ui/settings/SettingsImportScreen.kt app/src/main/java/com/controleco/app/ui/AppNav.kt app/src/test/java/com/controleco/app/data && git commit -m "feat: add ledger backup restore UI"`。

### Task 4: 文档回填、整合验证与 PR 准备

**Files:**
- Modify: `docs/自动记账捕获机制.md`
- Read: `docs/开发日志.md` only if this repository requires release notes for feature work; do not append a routine development log unless project convention requires it
- Verify: all files listed in Tasks 1–3

**Interfaces:**
- 文档说明文件格式版本、导出/恢复入口、整体替换行为、备份范围/排除项、财务数据隐私和新签名安装时的恢复用途。
- 文档不得声称能恢复设置、月预算、待确认交易、捕获日志或溯源记录。

- [ ] **Step 1: 更新捕获/账本文档**，用实际 UI 名称描述操作路径，解释建议先将 JSON 备份复制到手机以外的安全位置。
- [ ] **Step 2: 运行所有与备份直接相关的测试**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*LedgerBackup*' --tests '*LedgerBackupViewModelTest'`。
- [ ] **Step 3: 构建 debug APK**：`ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:assembleDebug`；确认生成 `app/build/outputs/apk/debug/app-debug.apk`，检查 manifest 权限没有新增广泛存储权限。
- [ ] **Step 4: 在测试设备上做端到端往返**：从含不同来源/退款/自定义分类图片的测试账本导出 JSON；确认 JSON 可解析且无捕获原文；清空/替换仅限测试设备的数据后恢复；核对账户、分类、交易与学习规则数量、主键关联和图片显示；确认范围外捕获/溯源/设置不被恢复流程删除。生产用户设备不得用作破坏性恢复测试。
- [ ] **Step 5: 最终核验 PR 范围**：检查当前分支仅含备份功能代码、测试、文档与本计划；保留已有 `tools/category_model/__pycache__/`，不纳入暂存；整理 PR 标题 `feat: add local ledger backup and restore` 与测试结果，不自动提交最终 PR 或推送。

## Risks and stop conditions

- 备份 JSON 与图片附件含敏感财务信息；不提供云上传，不将内容打印到日志，不在测试 fixture 中放真实用户数据。
- 当前 Android API 使用文件选择器，因此目标目录写权限由用户授予；取消选择不得报作导出成功。
- `transactions` 有账户/分类外键与唯一 `externalId` 索引，恢复必须校验并按 FK 顺序在事务内替换。
- 数据库表以外的图片文件与 Room 事务不能天然原子提交；采用临时文件、原子重命名、DB 失败补偿删除，并确保旧图片在 DB commit 前不清理。
- 文件格式未来演进必须增加 format version 和对应迁移/拒绝逻辑；不允许把未知版本当 v1 解析。
- 若设备/Android 提供者写流失败、备份尺寸超过限制、图片附件无效或恢复校验有错误，停止恢复并保持现有数据库不变。
- 核心账本恢复不改变 Room schema version，也不能触及排除范围表及设置偏好。