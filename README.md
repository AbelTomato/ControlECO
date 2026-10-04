# AccBook

安卓记账应用，目标是逐步实现"自动记账"：自动捕获微信支付、支付宝、银行动账短信等交易事件生成账单。

## 当前状态：阶段一（手动记账 + 账单导入）

- 手动记一笔 / 编辑 / 删除，月度流水与统计
- 账户、分类管理（内置默认数据）
- 微信 / 支付宝账单 CSV 导入（自动识别格式、预览、按交易单号去重）
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

## 后续路线（详见开发方案）

阶段二：通知监听通道（收款/动账提醒）+ 待确认队列；阶段三：无障碍抓取支付页 + 悬浮确认 + 分品牌保活引导；阶段四：银行短信通道 + 规则编辑器；阶段五：四品牌真机实测与保活加固。
