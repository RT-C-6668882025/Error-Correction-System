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
├── agent/                F7 四个独立任务 + F8 抽检 + F1.1 识别（含选择题剥离）
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
./gradlew -p logic-tests test      # 66 个用例
```

`logic-tests` 是独立构建，把 `app/src/main/kotlin/com/ecs/core` 与 `.../agent`
作为源目录直接编译，不是副本，改一处两边同步。

## CI 与发版

`.github/workflows/ci.yml` —— 每次 push 与 PR：跑算法层 66 个用例，构建 debug APK，
两者都作为 artifact 上传。算法层那个 job 不需要 Android SDK，几十秒出结果。

`.github/workflows/release.yml` —— 打 `v*` tag 触发（也可手动 `workflow_dispatch` 传版本号）：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

流程是：跑测试 → 构建 release APK → 建 GitHub Release 并附上 `ecs-<版本>.apk`。
`versionName` 取自 tag，`versionCode` 取自 workflow 的 run number，都用 `-P` 注入，
不需要改 `build.gradle.kts`。

**签名。** 配了下面四个 secret 就正式签名，没配就用 debug 密钥——
产物照样能装，但和正式签名的版本不能互相覆盖升级，发版说明里会写明这一点。

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64：`base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

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

**选择题只剥不存。** 单选题的考点与语法填空完全重合，只是包装成四选一。
剥掉选项后按填空录入：正确选项内容进 `answer`，选项本身在识别阶段之后即丢弃，
`section` 仍写「语法填空」，不新增字段也不新增枚举值。
用户选了哪个同样不存——理由和不存 `user` 一样，那是概率信息，错一次会污染全部下游分析。
选项同词根时（develop / developing / development / developed）取原形写进 `given`，
词根对不上就留空，宁可空着也不写进一个错的原形。

**剥离后推不出形态的题会被挡下来。** 标注时选项进输入不进输出：题眼只描述题干里的客观特征，
`选项 / 排除 / A项` 这类词进校验黑名单。若剥离后这道题的答案根本不是「某个词该长什么形态」，
Agent 输出 `not_form`，记录标为不完整进人工队列，不强行标注。
这是筛子：真正属于填空类考点的选择题顺利通过，不属于的挡在聚合之外。

**本地 embedding。** 字符 unigram + bigram 哈希到 256 维并归一化，离线、确定性。
它算的是字面重合度而非语义相似度；够用是因为检索只负责挑出 5 个候选，判断由 Agent 做。
要换语义模型，替换 `Embedder.embed` 一个函数即可。

## API 与模型

**端点不写死在代码里。** 任何说 OpenAI 兼容或 Anthropic Messages 协议的服务都能接：
GLM、Kimi、MiniMax、海内外中转站。设置页可增删端点，每个填名称、地址、协议、Key。

地址随便粘，`normalizeUrl` 会补成完整请求路径——中转站的地址用户可能写成裸域名、
带尾斜杠、只到 `/v1`、或已经是完整路径，差一个字符就是 404，而用户看到的只有「调用失败」：

```
https://x.com                     → https://x.com/v1/chat/completions
https://x.com/v1                  → https://x.com/v1/chat/completions
https://open.bigmodel.cn/api/paas/v4 → …/v4/chat/completions
https://relay.com/v1  (Anthropic) → https://relay.com/v1/messages
```

预置四个端点（Anthropic、智谱 GLM、Kimi、MiniMax），URL 与 Key 都可改、不可删。
每个端点有「测试连接」按钮：地址、协议、Key 三者错任一个报错都长得一样，
这个按钮把原始状态码和响应片段直接摆出来。

模型分两档，各自选端点 + 填模型 ID：

| 档位 | 用途 | 默认 |
|---|---|---|
| 视觉模型 | 拍照识别题号、题干、括号提示词、选项版式 | `glm-4.6v-flash`（免费） |
| 文本模型 | 考点树生成、标注、抽检、报告叙述 | `claude-opus-5` |

识别默认挑免费模型：录入频率是系统生命线，识别不该按次心疼。
标注那一档不建议为省钱降配，它直接决定聚合是否可信。
OpenAI 兼容端的 `temperature` 区间不含 0（智谱如此，多数中转站跟随），客户端自动贴下界。

## 提示词

九段系统提示词全部可看、可改、可还原（设置页 → 查看与编辑提示词）。
模型判断得准不准，一半取决于这些提示词，藏起来没有道理。

每段拆成两半：

- **正文** 可改：语气、判断标准、你自己想加的规则
- **契约** 只读：输出的 JSON 结构、文案禁止项、题眼禁用词，保存时自动拼在末尾

这么拆是因为输出结构是解析的前提，改坏了整条链路会**静默失败**——报告照出，只是全空。
契约锁死之后，正文清空也毁不掉解析（有测试盯着这一点）。
「还原默认」删的是你的覆盖值，不是把提示词清空。

## 检查更新

设置页对比 GitHub 上最新 Release 的版本号，有新版就显示版本号与更新说明，
点一下用系统浏览器打开 APK 直链。不申请安装权限，不做应用内安装。
当前版本从 `PackageManager` 读，不走 `BuildConfig`（AGP 8 默认不生成它）。
离线或接口失败时显示原因，不崩。

## 联网范围

识别、标注、报告生成、树生成与维护需要 API Key（设置页按端点分别填写）。
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
| 14 | 四选一剥离后生成 section=语法填空 的记录，库中无 options | `ChoiceStripper` / 导出包不含任何选项数据 |
| 15 | 该记录的 eye 不出现选项相关词汇 | `Validation.EYE_BANNED_CHOICE` |
| 16 | 剥离后推不出 form_rule 的题标为不完整，不进聚合 | `Annotator` 的 `not_form` 分支 |
