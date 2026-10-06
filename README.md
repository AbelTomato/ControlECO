# AccBook

安卓记账应用，目标是逐步实现"自动记账"：自动捕获微信支付、支付宝、银行动账短信等交易事件生成账单。

## 当前状态：阶段二（通知监听采集通道）

- 阶段一：手动记一笔 / 编辑 / 删除，月度流水与统计；账户、分类管理；微信 / 支付宝账单导入（xlsx / CSV，自动识别格式、预览、去重、退款标记）
- 阶段二：**通知监听自动捕获**——授予"通知使用权"后，微信收款、支付宝收款、云闪付动账等通知自动解析进入"待确认队列"，确认后入账
- 调试支持（debug 构建）：每条通知全量 Logcat 日志（tag=AccBook）+ 应用内"通知捕获日志"页（可记录所有应用的通知，点击复制原文），方便用真实样本调试规则
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

## 文档

- [docs/开发方案.md](docs/开发方案.md) —— 总体方案（含后续自动记账各阶段的设计结论与设计）
- [docs/开发日志.md](docs/开发日志.md) —— 逐日开发日志、决策记录与踩坑
- [docs/通知规则维护指南.md](docs/通知规则维护指南.md) —— 遇到新通知格式时如何添加/删除识别规则（工作流 + 修改点速查）
- [docs/阶段三交接-无障碍通道.md](docs/阶段三交接-无障碍通道.md) —— 阶段三（无障碍采集 + 悬浮确认）开工说明，供接手者使用

## 后续路线（详见开发方案）

阶段三：无障碍抓取支付页 + 悬浮确认 + 分品牌保活引导；阶段四：银行短信通道 + 规则编辑器；阶段五：四品牌真机实测与保活加固。
