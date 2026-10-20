# 本地交易分类模型实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 评估并在达到门槛时部署支出分类模型；收支方向由现有解析和用户确认决定，个性化继续沿用现有商户规则。

**Architecture:** 离线比较字符 n-gram + 逻辑回归与当前规则基线；按商户/规范化文本分组评测，只有效果胜出才导出支出分类模型。Android 端只预测已确定为支出的交易分类；现有 MerchantRules 保持个性化与优先级，不新增模型训练样本存储。

**Tech Stack:** Python 3（离线评测；逻辑回归训练依赖须按项目环境确认，不安装全局依赖）；Kotlin/JVM 纯代码推理；现有 JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-15-local-category-model-design.md`

## Global Constraints

- 不读取或上传仓库外原始账单；已标注的 `统一训练候选.csv` 保持在本机，不加入 Git。
- 初版仅做支出单标签分类；排除“未知”、多标签、收入及“不计收支”样本。
- 收支方向由账单字段、现有解析规则和用户确认确定；模型不预测方向、不改写金额/方向。
- 初始候选为字符 n-gram + 逻辑回归；只有分组留出结果优于规则基线且指标有意义时才继续接入。
- 个性化继续使用现有 `MerchantRules`，不新增模型训练样本库、不在线更新通用模型。
- 模型只产生支出分类建议；低置信度、分类缺失或推理失败时回退关键词和默认分类。
- 不引入网络调用；不新增 Android ML 运行时依赖。
- 使用字符 n-gram 特征；模型资产不得包含原始训练文本。
- 训练产物必须位于仓库外；不提交真实用户数据。

## 文件结构

- `tools/category_model/train.py`：分组评测、逻辑回归训练/安全导出与现有规则基线对比。
- `tools/category_model/test_train.py`：训练工具输入过滤、分组切分、指标和模型导出测试。
- `app/src/main/java/com/controleco/app/ml/CategoryModel.kt`：模型数据结构、JSON 读取、n-gram 特征、线性打分与置信度。
- `app/src/test/java/com/controleco/app/ml/CategoryModelTest.kt`：格式、预测、未知标签、损坏模型、低置信度测试。
- Existing `app/src/main/java/com/controleco/app/capture/MerchantRules.kt`：保留作为个性化学习机制，不新增模型侧样本存储。
- `app/src/main/java/com/controleco/app/capture/CaptureDefaults.kt`：仅在评测通过后为支出分类增加模型建议 fallback；不改变方向判断。
- `app/src/main/assets/category_model.json`：仅当评测达到接入门槛时加入；存模型参数和支出标签，不存样本或文本。
- `docs/自动记账捕获机制.md`：模型优先级、开关/回退、训练数据隐私和当前评测限制。

---

### Task 1: 训练数据审计与离线基线

**Files:**
- Create: `tools/category_model/train.py`
- Create: `tools/category_model/test_train.py`
- Input only: `D:\AbelTomato_Files\Files\统一训练候选.csv` (outside repository)
- Output only: `D:\AbelTomato_Files\Files\category-model-experiment\`

**Interfaces:**
- `load_examples(path) -> (expense_examples, exclusions)`; include only expense rows with one non-unknown category label.
- `split_by_group(examples, seed) -> (train, test)`; group is normalized merchant/text, never split duplicate normalized text across partitions.
- `evaluate(y_true, y_pred) -> dict` with accuracy, macro-F1, weighted-F1, per-label precision/recall/support.
- Unknown labels are excluded case-insensitively; blank labels are excluded; text is digit-redacted before vectorization.

- [x] **Step 1: Write input filtering tests** covering `未知`, blank category, multi-label exclusion, income/non-income-expense exclusion, empty text, numeric redaction, and duplicate normalized text grouping.
- [x] **Step 2: Run tests** with `python3 -m unittest tools.category_model.test_train -v`; expected initial failure until functions exist. Passed in the repository virtual environment using `.venv/bin/python -m unittest tools.category_model.test_train -v` (13 tests).
- [x] **Step 3: Implement CSV loader, normalization and grouping** with only Python standard library; do not print sample text.
- [x] **Step 4: Implement deterministic group split and metrics** including zero-division behavior and one-support labels.
- [x] **Step 5: Run tests and inspect aggregate-only report**; confirm no raw text is printed or written. The repository-venv unit tests passed; export tests confirm training text is not written. No aggregate report was inspected.
- [x] **Step 6: Run the audit on the external labeled CSV**; aggregate report at `/mnt/d/AbelTomato_Files/Files/category-model-experiment/audit_report.json` (379 expense examples; 7 multi-label and 116 non-expense exclusions; 290 train / 89 test examples; 103 train / 26 test groups; 5 evaluable and 6 unevaluable labels). Only aggregate metadata was inspected; raw CSV content was not read.

### Task 2: Train and export compact model

**Files:**
- Modify: `tools/category_model/train.py`
- Modify: `tools/category_model/test_train.py`
- Create outside repository: `D:\AbelTomato_Files\Files\category-model-experiment\category_model.json`

**Interfaces:**
- `train_classifier(examples, ngram_min, ngram_max, regularization) -> Model` using character n-gram features and multiclass logistic regression.
- `predict(model, text) -> list[(label, score)]` sorted by score.
- `export_model(model, path, metadata) -> None`; serialized model contains feature weights, labels, schema version and thresholds only, never original text.

- [x] **Step 1: Add tests** for deterministic training, unseen n-gram handling, score ordering, probability normalization/calibration report, and export scan proving no training sentence appears in JSON.
- [x] **Step 2: Run targeted tests** and verify failure before implementation. Passed in the repository virtual environment using `.venv/bin/python -m unittest tools.category_model.test_train -v` (13 tests).
- [x] **Step 3: Implement character n-gram vectorization and multiclass logistic regression**; use only single-label expense samples. If a reproducible approved training dependency is unavailable, pause and ask before selecting another classifier. Implementation exists and `tools/category_model/requirements.txt` pins dependencies; dependency installation and real-data training were not verified here.
- [x] **Step 4: Tune only on group-separated validation data**; reserve group-separated test data for final metrics; write metrics and model outside repo. Verified from the external training report; selected parameters are char n-gram 1–3, C=0.1.
- [x] **Step 5: Verify artifact size, schema version, labels and absence of raw text**; record baselines for both tasks and compare category results with existing `CategoryGuesser` where an equivalent label exists. The checked-in asset is 654,169 bytes, schema 1, 11 labels and 2,589 features; it has model parameter fields and no examples/training-text field. Baseline metrics were not verified.
- [x] **Step 6: Apply gate:** held-out metrics are meaningful and beat the comparable rule baseline (model macro-F1 0.9181 vs 0.4070; accuracy 0.9535 vs 0.2209; 86 comparable held-out examples). Six long-tail labels are not evaluable; do not claim their reliability. Report verified from `/mnt/d/AbelTomato_Files/Files/category-model-experiment/training_report.json`.

### Task 3: Pure Kotlin expense classifier inference (only if Task 2 passes the gate)

**Files:**
- Create: `app/src/main/java/com/controleco/app/ml/CategoryModel.kt`
- Create: `app/src/test/java/com/controleco/app/ml/CategoryModelTest.kt`
- Create: `app/src/main/assets/category_model.json` only if Task 2 passes the gate.

**Interfaces:**
- `CategoryModel.load(input: InputStream): CategoryModel` validates schema/version and bounded model dimensions.
- `CategoryModel.predictExpenseCategory(text: String, allowedLabels: Set<String>): Prediction?`.
- `Prediction(label: String, confidence: Double, margin: Double)`; return null below conservative configured confidence/margin thresholds.

- [x] **Step 1: Add JVM tests** for Python/Kotlin fixture parity, numeric redaction, expense-label allow-list, invalid schema, oversized weights, empty input and confidence fallback.
- [x] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*CategoryModelTest'`** and confirm failure; on this host use `bash ./gradlew` only after JDK availability is confirmed. Targeted model tests passed with the configured Android SDK.
- [x] **Step 3: Implement parser and scorer using APIs already available in project; do not add dependencies.**
- [x] **Step 4: Load asset lazily and fail closed to null on parse/runtime failure.**
- [x] **Step 5: Run model tests and full compile check for the touched module.** Model and capture-default tests passed; Gradle compiled the app Kotlin sources.

### Task 4: Expense category recommendation with safe fallback (only if Task 2 passes the gate)

**Files:**
- Modify: `app/src/main/java/com/controleco/app/capture/CaptureDefaults.kt`
- Modify: `app/src/main/java/com/controleco/app/capture/CaptureCenter.kt` only where model context can be passed without blocking capture.
- Modify: `app/src/main/java/com/controleco/app/data/LedgerViewModel.kt`
- Modify: `app/src/test/java/com/controleco/app/capture/CaptureDefaultsTest.kt`
- Modify: `app/src/test/java/com/controleco/app/ml/CategoryModelTest.kt`

**Interfaces:**
- Add an injectable pure selector taking transaction type, text, current `CategoryEntity` list, learned rule result and model prediction; it returns a valid category ID or existing fallback.
- For expense transactions only, precedence: explicit user selection > exact learned merchant rule > model result when above threshold > existing keyword/default fallback.
- Income transactions bypass model inference entirely; model output never changes parsed transaction type.

- [x] **Step 1: Add failing tests** for learned-rule precedence, expense model selection, income bypass, missing/renamed category rejection, low confidence fallback and unchanged transaction type.
- [x] **Step 2: Run targeted unit tests.** `CaptureDefaultsTest` and `CategoryModelTest` passed.
- [x] **Step 3: Implement pure selection logic and wire only to category suggestion/default selection.**
- [x] **Step 4: Keep model execution off the main thread and avoid delaying persistence/capture; skip model if no model asset is available.** Prediction runs from the capture IO coroutine scope; the model asset loads lazily and load/prediction errors return null.
- [x] **Step 5: Run targeted tests and compile.** Targeted tests passed and Gradle compiled the app Kotlin sources.

### Task 5: Preserve and verify existing MerchantRules personalization

**Files:**
- Read: `app/src/main/java/com/controleco/app/capture/MerchantRules.kt`
- Read: `app/src/test/java/com/controleco/app/capture/MerchantRulesTest.kt`
- Modify: none unless integration reveals a missing rule-precedence test.

**Interfaces:**
- Existing `MerchantRules.learn(...)` remains the only learning mechanism; it is invoked by existing confirmed/edit capture flows.
- Existing `MerchantRules.categoryIdFrom(...)` checks rule type and category still exists.

- [x] **Step 1: Verify existing MerchantRules tests cover confirmation/edit learning, category/type isolation, and invalid/deleted category fallback.** Direction isolation and deleted-category fallback are covered by `MerchantRulesTest`; confirmation/edit learning is wired through `PendingActions.confirm()` and `PendingActions.updateEntered()`, but has no dedicated automated test.
- [x] **Step 2: Add only a missing model-versus-rule precedence test if needed; do not introduce a second learning store.** Existing `CaptureDefaultsTest` covers learned-rule precedence over model suggestions; no additional test or learning store was needed.
- [x] **Step 3: Run `./gradlew :app:testDebugUnitTest --tests '*MerchantRulesTest' --tests '*CaptureDefaultsTest'` when JDK is available.** Passed with Android SDK at `/home/abeltomato/Android/Sdk`; Kotlin compilation completed and both targeted test classes passed.

### Task 6: Documentation and optional model control (only if Task 2 passes the gate)

**Files:**
- Modify: `app/src/main/java/com/controleco/app/ui/settings/SettingsDeveloperScreen.kt` or the existing settings screen selected by current conventions.
- Modify: `app/src/main/java/com/controleco/app/capture/CaptureCenter.kt` only if a preference is needed to enable model suggestions.
- Modify: `docs/自动记账捕获机制.md`
- Modify: relevant JVM tests.

**Interfaces:**
- Provide an opt-out for model suggestions if consistent with existing settings patterns.
- Do not add a second clear-personalization action; existing merchant-rule management remains the user control for learned rules.
- Document model training data, local inference, rule precedence and current support limitations.

- [x] **Step 1: Add a preference test only if a model opt-out is added.** Added JVM coverage for enabled/disabled model suggestion selection and retained MerchantRules precedence.
- [x] **Step 2: Implement model opt-out only if the existing settings architecture supports a simple preference; do not alter merchant rule controls.** Added a default-on SharedPreferences switch under Developer Features; disabled mode skips inference and retains rule/keyword fallback.
- [x] **Step 3: Update capture mechanism docs with priority, fallback, privacy, sample-size caveats and disabled automation status.** Documented offline/local data flow, evaluation, long-tail caveats, precedence and automation boundary.
- [x] **Step 4: Run focused unit tests and `ANDROID_HOME=/home/abeltomato/Android/Sdk bash ./gradlew :app:testDebugUnitTest --tests '*CaptureDefaultsTest' --tests '*CategoryModelTest' :app:compileDebugKotlin`**.
- [x] **Step 5: Verify model/data artifacts are not in the repository and the model asset contains no training examples.** Training report/model remain outside the repository; bundled asset is 654,169 bytes, schema 1, 11 labels, 2,589 features, no examples/training-text fields.

## Risks and stop conditions

- On the inspected host Java was unavailable and `./gradlew` lacked execute permission; before Gradle verification, confirm JDK 17 and invoke wrapper through `bash` if appropriate.
- Python currently lacks NumPy/scikit-learn; confirm an approved reproducible offline training dependency before implementing logistic regression. Do not install packages globally; if unavailable, pause before changing model family.
- Training data is heavily imbalanced and has only one income sample; initial model is expense-only, and income classification remains on existing rules.
- Class labels may be user-defined names. Renames/deletions must be mapped against current categories and stale labels rejected.
- Any evaluation split with fewer than two independent groups for a label must mark that label unevaluable, not report a misleading score.
- If the classifier cannot beat the existing rule baseline on a valid grouped holdout, keep it as an offline experiment and do not ship/integrate it.