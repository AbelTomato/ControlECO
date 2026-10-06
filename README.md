# AccBook

安卓记账应用，目标是逐步实现"自动记账"：自动捕获微信支付、支付宝、银行动账短信等交易事件生成账单。

## 当前状态：阶段四（UI 美化）✅ 完成 · v0.4.0

- 阶段一：手动记一笔 / 编辑 / 删除，月度流水与统计；账户、分类管理；微信 / 支付宝账单导入（xlsx / CSV，自动识别格式、预览、去重、退款标记）
- 阶段二：**通知监听自动捕获**——授予"通知使用权"后，微信收款、支付宝收款、云闪付动账等通知自动解析进入"待确认队列"，确认后入账
- 阶段三：**无障碍页面采集 + 悬浮确认窗 + 退款关联**——支付宝支付页自动补全收款方/支付方式；悬浮卡与横幅通知双通道提醒（双向冲突处理）；无金额退款通知→手动关联原账单；安卓 13+ 侧载受限设置与分品牌支付保护引导。微信页面通道经实测窗口不可读已关停，微信走通知通道
- 阶段四：**UI 美化**——设计 token 体系（暖白/暖墨/暖金语义色、tnum 金额字形）；新增首页预算 HUD（剩余可用/已花进度/建议日花三态）与快速记账页（金额优先+数字键盘）；导航重构为 首页/账单/统计/设置；账单页成熟化排版；统计页结论>趋势>分类细节（自绘图表）
- 调试支持（debug 构建）：通知与页面快照全量 Logcat 日志（tag=AccBook）+ 应用内"捕获日志"页（可记录所有应用的通知，点击复制原文）+ 20 秒延迟弹窗/通知测试
- 数据全部保存在本机（Room 数据库），无网络权限

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
"$ADB" logcat -v time -s AccBook            # 实时日志：本应用全部（[Ntf] 通知 / [Page] 页面）
"$ADB" logcat -v time -s AccBook | grep Page    # 只看页面通道（页面快照/规则判定/悬浮窗原因）
"$ADB" logcat -v time -s AccBook > accbook.log  # 边看边存文件，方便把样本发给开发会话
```

5. **采样流程**（页面规则调试）：`logcat -c` 清屏 → 手机上完整走一遍支付 → 终端里 `snapshot pkg=... texts=[...]` 就是页面文本快照，复制出来对照修改 `PageRules.kt`（流程见维护指南第五节）。

## 文档

- [docs/开发方案.md](docs/开发方案.md) —— 总体方案（含后续自动记账各阶段的设计结论与设计）
- [docs/开发日志.md](docs/开发日志.md) —— 逐日开发日志、决策记录与踩坑
- [docs/通知规则维护指南.md](docs/通知规则维护指南.md) —— 遇到新通知/页面格式时如何添加/删除识别规则（工作流 + 修改点速查）
- [docs/阶段三交接-无障碍通道.md](docs/阶段三交接-无障碍通道.md) —— 阶段三开工说明（已完成，留作设计依据）
- [docs/阶段四交接-UI美化.md](docs/阶段四交接-UI美化.md) —— 阶段四（UI 美化）说明，含 UI 现状盘点、硬约束与完成情况（第八节）

## 后续路线（详见开发方案）

阶段四：UI 美化（已完成，真机走查遗留见交接文档第八节）；阶段五：银行短信通道（顺延，等真实样本）+ 规则编辑器 + 真机实测与保活加固。
