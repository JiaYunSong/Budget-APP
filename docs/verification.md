# 1.0.0 验证记录

验证日期：2026-10-05。当前云实例中执行了安装脚本、构建脚本和原生 Android 测试；本记录不表示所有品牌实体手机已逐一测试。

## 结果

| 检查 | 实际结果 |
| --- | --- |
| `scripts/cloud-setup.sh` | 实际执行成功；官方工具校验、SDK 安装、Debug 构建和测试完成 |
| `testDebugUnitTest` | **33 项通过，0 失败，0 跳过** |
| AndroidJUnitRunner | **3 项通过**，`OK (3 tests)`，`INSTRUMENTATION_CODE: -1` |
| `lintDebug` / `lintRelease` | 0 错误；2 个依赖更新提示，保留已验证的固定版本 |
| Release 构建 | 成功，独立 Release 密钥签名 |
| `apksigner verify --verbose` | 1 个签名者，APK v2 签名验证通过 |
| `zipalign -c -P 16 -v 4` | 验证成功，含原生库的通用包按 16KB 对齐 |
| `sha256sum -c releases/SHA256SUMS` | 通过 |
| APK manifest | 包名 `com.local.deposittracker`，1.0.0，minSdk 26，targetSdk 37，无 INTERNET、存储、通知或其他敏感权限 |
| APK 架构 | arm64-v8a、armeabi-v7a、x86、x86_64 |
| 正式 APK 安装 | Android 15 / API 35 的无 GMS AOSP 模拟器，`pm install` 返回 Success |
| 正式 APK 冷启动 | `am start -W` 返回 Status: ok，MainActivity 正常启动，非 Debuggable |

manifest 仅有 AndroidX 动态接收器所需的应用自定义签名级权限，不向其他应用开放，不赋予联网能力。

原始记录：[JVM 测试](validation/unit-tests.xml)、[Android 测试](validation/android-tests.txt)、[Release lint](validation/lint-release.txt)。APK 校验值在 [SHA256SUMS](../releases/SHA256SUMS)。

## 业务覆盖

- 10 万活期转入 5 万定期：活期 5 万、定期 5 万，总资产不变。
- 5 万本金、2% 年利率、365 天：本息 51000 元；实际天数例子 92 天、1.35% 得利息 170.14 元。
- 到期只增加实际利息；提前支取使用实际回款，后续不会再次到期入账。
- 实际提前支取不能使用未来日期；编辑不会更改本金，已结算产品不可修改回款账户。
- 漏开三个月补记月存；连续打开十次无重复；31 日在平年 / 闰年二月按月底执行。
- 开始 / 结束日期边界、系统日期倒退、余额不足及明确允许负余额、负余额部分修复。
- 来源账户 / 到期账户异常时回退与待处理账户；账户转账资产守恒。
- 转存链及重复转存拒绝，删除规则保留历史，排除账户同步排除其来源定期。
- JSON 恢复全部字段、资产、历史和转存关系，拒绝错误版本及重复 ID。
- CSV BOM、中文、引号 / 逗号 / 多行备注、公式注入保护、重复跳过、错误行号、历史重复回款防护。
- 日历不会把已经执行的月存再作为计划计入。

## Android 原生测试

1. Room 事务余额不足时整体回滚，并验证 JSON 保存 / 恢复。
2. 真正关闭再打开磁盘数据库，余额及所有字段保持一致。
3. 首次欢迎 → 创建 10 万元活期 → 创建 5 万元定期 → 验证总资产不变 → 月 / 年日历 → 全部流水 → 存款详情 / 转存历史 → 设置备份入口。

云机器没有 KVM，采用无 GMS AOSP x86_64 API 35 软件模拟器。初次高分辨率运行出现系统 UI 卡顿，降低为 540×960 / 210dpi 并关闭模拟器动画后完成全部测试。最初 UI 测试没有滚动到屏幕外的 LazyColumn 卡片，已修正为明确滚动后验证；最终结果是三项实际执行且全部通过，没有跳过断言。

原生 ADB 试图写入只读用户目录，测试使用 `adb-shell==0.4.4` 的本地 TCP 连接，通过 `scripts/emulator-tests.py` 安装 APK 并运行 AndroidJUnitRunner。这是开发环境连接方式，与应用运行无关。

## 限制与后续

- 到期提醒为应用内提示，关闭应用时不发送通知；这符合第一版允许的范围。
- 未逐一测试华为、小米、OPPO、vivo、荣耀实体机；APK 不依赖 GMS，最低版本由 manifest 和构建检查确认。
- 第一版 schema 版本为 1，无前一版本可迁移；未来升级必须增加明确 Migration 和迁移测试，禁止破坏性迁移。
- GitHub Actions 已配置；本地通过不等于远端 CI 的运行结果。
- 签名密钥保存在当前云实例的 `/workspace/private/`（Git 仓库之外），未上传 GitHub；后续覆盖升级需保留相同密钥。
- 安装 / 启动配置已保存为云环境草稿；发布或在新任务恢复的结果需由平台另行确认。
