# FitCoach 运动建议

读取 Android **Health Connect** 中的运动数据（Google Fit、Fitbit、三星健康、Garmin 等都会同步到这里），
分析最近 28 天的训练负荷和身体恢复情况，给出当天或第二天的建议：**加量 / 维持 / 减量 / 休息**，
并附上建议时长、心率区间和判断依据。

> 为什么不用 Google Fit API：Google 从 2024 年起已弃用 Google Fit API，并要求开发者在 2026 年前迁移到 Health Connect。

## 项目结构

```
engine/   纯 Kotlin 推荐引擎，不依赖 Android，可直接跑单元测试
  Models.kt               数据模型 (DailyMetrics, Recommendation, Signal…)
  TrainingLoad.kt         训练负荷 (TRIMP) 计算，含无心率时的降级方案
  RecommendationEngine.kt 核心决策逻辑
  SampleData.kt           5 种模拟场景，用于演示模式和测试
  cache/MetricsCache.kt   本地缓存与增量合并（去重）逻辑
app/      Android 应用 (Kotlin + Jetpack Compose)
  data/HealthConnectRepository.kt  所有 Health Connect 调用（并行读取、变更令牌）
  data/HealthSyncRepository.kt     同步策略：首次完整导入，之后增量更新
  data/CacheStore.kt               缓存存成本机 JSON 文件
  data/ProfileRepository.kt        用户资料
  ui/MainViewModel.kt              状态管理
  ui/MainScreen.kt                 界面
  ui/Texts.kt                      中文文案
```

## 算法

1. **每日负荷**（Banister TRIMP）：`时长 × HRR × 0.64 × e^(1.92 × HRR)`，其中 HRR 是心率储备百分比。
   - 运动记录没有心率时按中等强度 (HRR 0.6) 估算；
   - 只有步数时，把超过 3000 步的部分折算成步行分钟，按低强度估算。
2. **急慢性负荷比 ACWR** = 近 7 天日均负荷 ÷ 近 28 天日均负荷（至少需要 14 天有数据）。
   | ACWR | 基础建议 |
   |---|---|
   | < 0.8 | 加量 |
   | 0.8 – 1.3 | 维持 |
   | 1.3 – 1.5 | 减量 |
   | > 1.5 | 减量（高风险） |
3. **恢复信号扣分**（中度 1 分，重度 2 分）：
   - 静息心率比基线（28 天中位数）高 5 / 8 bpm 以上
   - HRV 低于基线的 85% / 75%
   - 昨晚睡眠不足 6h / 5h，或近 3 晚平均不足 6.5h
   - 连续训练 5 / 7 天以上；一周训练单调度 > 2；上一次训练量超过平时的 1.6 倍
   
   扣 1–2 分降一档，3–4 分降两档，≥5 分直接休息。
4. **目标量**：以"平时一次训练的负荷"为基准，加量 ×1.1、维持 ×1.0、减量 ×0.6，
   同时限制加量后的 ACWR 不超过 1.3，再按对应心率区间换算成分钟数。

5. **按年龄 / 性别个性化**（用户在"修改资料"里填写，保存在本机）：
   | 条件 | 调整 |
   |---|---|
   | 填了实测最大心率 | 直接使用，优先于公式 |
   | 女性 | 最大心率用 Gulati 公式 `206 − 0.88 × 年龄`；TRIMP 用女性系数 `0.86 · e^(1.67x)` |
   | 男性 / 未填写 | 最大心率用 Tanaka 公式 `208 − 0.7 × 年龄`；TRIMP 用 `0.64 · e^(1.92x)` |
   | ≥ 50 岁 | 加量幅度 10% → 5%；连续训练 4 / 6 天即提醒（原为 5 / 7） |
   | ≥ 65 岁 | ACWR 安全上限 1.3 → 1.2 |
   | < 18 岁 | 睡眠阈值各提高 1 小时（推荐 8–10 小时） |

   静息心率、HRV 的基线本身就是用户自己最近 28 天的数据，已经天然个性化。

所有通用阈值都在 `EngineConfig` 里，可以调整。

## 数据同步

- **首次**：完整导入最近 30 天，存到本机缓存，同时向 Health Connect 要一个"变更令牌"。
- **之后**：用令牌询问"上次之后哪些记录新增 / 修改 / 删除了"（Health Connect Changes API），
  只重新读取受影响的日期；最近两天总是重读一次（手表数据常有延迟）。
- **不重复**：每日数据以日期为键、运动记录以 Health Connect 记录 id 为键；更新时整段替换该日期区间，
  所以重复同步、中途中断都不会累加出重复数据。
- **自动回退到完整导入**：令牌过期（超过 30 天没打开）、授权变化、缓存文件损坏，或用户点"完整重新导入"。
- **其他提速**：各类数据并行读取；运动平均心率查询结果会缓存，记录没改就不再重复查询；
  打开 app 时先用缓存立即显示结果，再在后台同步。
- 缓存最多保留 180 天，因此会逐渐积累超过 Health Connect 默认 30 天读取限制的历史。

## 运行

**引擎测试**（只需要 JDK 17）：

```bash
./gradlew :engine:test --configure-on-demand
```

**Android 应用**：用 Android Studio 打开项目根目录，等 Gradle 同步完成后运行到手机或模拟器上。
- 需要 Android 8.0+；Android 14 及以上系统自带 Health Connect，更低版本需要从 Play 商店安装。
- 模拟器里通常没有健康数据，可以点页面底部的"演示场景"按钮查看效果，
  也可以在 Health Connect 的开发者选项或 Fitbit / Google Fit 中录入数据。

## 后续可以做的

- 训练目标设置（减脂 / 备赛 / 保持健康），据此调整强度分布
- 用 WorkManager 在后台定期同步（需要 Health Connect 后台读取权限），打开 app 时几乎无需等待；每天早上推送建议
- 记录用户的主观疲劳度 (RPE)，作为额外的恢复信号
- 上架 Google Play 前：正式隐私政策，以及 Health Connect 权限的声明审核
