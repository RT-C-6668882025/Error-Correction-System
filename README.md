# 错题元数据系统 v2.1 · Android

把**语法填空**与**完成句子**的错题压缩成一份结构化字段，使任何模型在不接触原题的前提下，
仅凭字段就能输出可执行的诊断报告与背诵材料。

Kotlin + Jetpack Compose + Room，单人使用，移动端优先。

## 分层

```
L0  元数据层   一个「空」= 一条记录     Room 持久化，唯一真实数据源
L1  小方向层   按考点聚合               实时计算，不落库
L2  大方向层   全局趋势与关联           实时计算，不落库
```

改了 L0，所有报告自动更新。

## 目录

```
app/src/main/kotlin/com/ecs/
├── core/                 纯 Kotlin，不依赖 Android，被 logic-tests 直接编译
│   ├── model/            L0 字段、五种状态、confidence 权重
│   ├── parse/            F1.3 错题号解析 `3, ?5, 12-2`
│   ├── tree/             考点树、Top-5 向量检索、本地 embedding、层级截断
│   ├── rules/            F1.6 校验与状态推导、2.4 题眼红线、4.4 休眠、5.3 文案禁止项
│   ├── agg/              §4 全部算法：加权计数 / hit_papers / 错误率 / 关联 / 成熟度 / 一致率
│   ├── report/           F3 F4 模板
│   └── export/           F5 导出包与 CSV 回读
├── agent/                F7 四个独立任务 + F8 抽检 + F1.1 识别
├── data/                 Room、考点树文件、设置、F5.3 自动备份、仓库
└── ui/                   Compose 九个页面
logic-tests/              独立 JVM 构建：编译 core/ 与 agent/ 并跑单元测试
```

## 构建

需要 Android SDK 34 与可访问的 Google Maven。

```bash
./gradlew :app:assembleDebug
```

算法层可以脱离 Android SDK 单独验证：

```bash
cd logic-tests && gradle test      # 66 个用例
```

`logic-tests` 把 `app/src/main/kotlin/com/ecs/core` 与 `.../agent` 作为源目录直接编译，
不是副本，改一处两边同步。

## 关键实现取舍

**form_rule 不由模型生成。** 它挂在考点树节点上，标注时由系统填充，UI 只读。
同一考点下的 form_rule 完全一致是结构保证的，不需要事后校验，也不会因措辞差异误报不一致。

**标注只给 Top-5 候选。** Agent 每次调用互相独立，把 180 个节点塞进上下文，
它对边缘节点的注意力必然衰减，第 50 条和第 300 条会给同类题不同路径，聚合直接裂开。
新建节点只发生在树维护任务里，标注环节禁止。

**数字本地算，判断交给模型。** 报告的统计部分全部由 `Aggregator` 算好再填模板，
模型只写「看到什么往哪走」。反过来做会得到算错但读着顺的报告。

**id 与主键分开。** PRD 的 `id` 只含题型+题号+空序，跨卷必然重复；
Room 主键用 `uid = "{paper}#{id}"`，同卷内 id 唯一的约束保持不变。

**录入永不阻断。** 极简录入只要卷名 + 错题号，其余留空，状态记为「不完整」。
唯一会拒绝写入的是 id 非法或同卷重复——那会破坏主键。
答案暂缺记「待补答案」，`total_in_section` 缺失的卷子既不计错误率的分子也不计分母。

**统计口径。** 只有「活跃」「休眠」参与数字；「休眠」参与聚合但不出现在报告里；
「归档」「待补答案」「不完整」不进入任何统计。

**本地 embedding。** 字符 unigram + bigram 哈希到 256 维并归一化，离线、确定性。
它算的是字面重合度而非语义相似度；够用是因为检索只负责挑出 5 个候选，判断由 Agent 做。
要换语义模型，替换 `Embedder.embed` 一个函数即可。

## 联网范围

识别、标注、报告生成、树生成与维护需要 API Key（设置页填写）。
其余功能——录入、列表、倒推表、聚合数字、导出、备份——全部离线可用。

不识别手写作答：识别错会连带污染题眼和后续全部分析，而这种噪声在聚合后看不出来，
报告依然体面，结论完全跑偏。作答改为直接输入错题号。

## 验收对照

| # | 标准 | 实现 |
|---|---|---|
| 1 | 极简录入 3 秒生成 status=不完整 记录 | `EntryScreen` 极简页 → `RecordRepository.quickAdd` |
| 2 | 完成句子一题两空拆两条 | `SlotSpec.parse("12-1, 12-2")` |
| 3 | 同 kaodian 下 form_rule 一致 | 结构保证：只从树节点带出，UI 只读 |
| 4 | 导出包交给全新模型能直接产出报告 | `Exporter.readme` 含字段词典、聚合口径、范例与禁止项 |
| 5 | eye + form_rule 可推出答案形态 | 题眼强校验：10-25 字、禁用词拦截 |
| 6 | 倒推表可单独导出、可遮答案自测 | `Exporter.reverseTable` / 倒推表页「遮住答案」 |
| 7 | 待补答案 / 不完整 不进统计 | `RecordStatus.countsInStats` |
| 8 | 优先级按 hit_papers | `Aggregator.kaodianStats` 排序键 |
| 9 | README ≤120 行 | `Exporter.README_MAX_LINES`，超出时裁考点清单 |
| 10 | 抽检 5% + 一致率 + <85% 告警 | `Verifier.sample` / 首页一致率看板 |
| 11 | <50 条报告入口置灰 | `Aggregator.maturity` |
| 12 | 树版本变更后可批量重映射 | `TreeStore.mutate` 递增版本 → `RecordRepository.remap` |
| 13 | 每 50 条自动备份，保留 5 份 | `BackupManager` |
