# 0.14.3 发布说明（versionCode 45）

本版主题：**启动故障可恢复性收口**（issue #309 闸门互锁修复）+ **引导页重做：Apple 式黑白极简**。

---

## 一、启动互锁修复（issue #309）：带病运行时不再「永远不启动也永远不自愈」

**缺陷现场**：闸门 A（`EngineManager.startEngine` 的 `liveRuntimeComplete()`，位置先于 force/可用性判定）
拒启 ⇒ 不 spawn ⇒ `engine.log` 永不产生 ⇒ 闸门 B（`EngineStartFlow` 读 `engine.log` 判自愈）结构性不可达。
issue 实测 36 次 / 47 分钟零恢复动作。

**修法**：拒启路径现在会先取证（`.runtime-tree-damaged` 标记）→ 删 `.snapshot-fingerprint` → 清刷新账本
→ 落诊断镜像 → 如实写 boot-fail，借既有冷启动分支走完整重抽取。**两道闸门**：每次运行一次的预算
（`runtimeTreeHealedThisRun`）+ 证据分级（只含快照自身条目，传递依赖命中仅记录不自动触发）。

**用户显式出口**：错误页主按钮在安全模式入口下可强制重做一次（放行分级、不放行预算）。

诊断包新增 `start_refusal_{code,confirmed,evidence}` 三字段；`EngineManager.invalidateSnapshotFreshness()`
删除指纹失败时如实回报而非承诺重抽取。

## 二、引导页重做：Apple 式黑白极简（PR #2）

- 画布从 teal 光晕渐变改为羊皮纸平色（浅色 `#F5F5F7` / 深色纯黑），单层 hairline 描边状态卡（18dp）。
- 主操作 = 墨色实心胶囊；次操作 = 描边胶囊；版本号改描边 tag；品牌图标内缩留呼吸位。
- **色相即信号**：状态圆点的「进行中」统一为墨色脉冲，红色只留给 Error/Closed；
  `ds_*` 铬面色板全部近中性灰，色相仅保留在 ok/warn/danger 语义族（新增 `GuidePaletteMonochromeTest` 守护）。
- 全部功能原样保留：启动引擎 / 打开控制台 / 检查更新 / 存储授权 chip / 复制日志五路交互、
  GuidePhase 状态机、不定进度、崩溃横幅与日志摘要、宽度钳制（平板/折叠屏 440dp 上限）。

## 三、维护面

- **PR #308 已合入**：AI root 授权开关 + 应用级 root 授权面与属主自愈（维护者修订版，CI 双绿）。
- **追上游 `0.2.0-rc.2`**：上游 `otel` 静态 import `got`，旧快照缺件即 `ERR_MODULE_NOT_FOUND`；
  已登记依赖并重建双 ABI 快照。
- **门禁实修**：`check-engine-overlay` 包根推导修正（`/lib/` 与 `/dist/` 取较后者）；
  `check-patch-mirror` 递归比对 tests/；`check-kotlin-test-count` 接进发布链（单测结果缺席判红）。

## 验证状态（如实）

- 本仓 CI：Kotlin 编译门禁 + 敏感信息扫描全绿；发布链内单测 + 注入一致性 + 签名指纹断言由本链自带。
- **三层设备验收（CDP/adb 真机用户层）未在本发布链内执行**——#309 的设备层用例见
  `docs/0.14.3-TESTER-CHECKLIST.md` I309；引导页视觉回归建议装机后双主题各看一眼。

## 资产

| 资产 | 说明 |
|---|---|
| `dsh-mobile-apk-v0.14.3-arm64.apk` | arm64 真机 |
| `dsh-mobile-apk-v0.14.3-x86_64.apk` | x86_64 模拟器 / 设备 |
| `snapshot-{arm64,x86_64}.tar.xz`(+sha256) | 注入后运行时快照（与 APK 内嵌同源） |
| `dsh-android-*.tgz` ×8 | 插件包（可单独更新） |

**ABI 必须与设备匹配**：不匹配会导致引擎启动即崩。真机选 arm64，模拟器选 x86_64。
