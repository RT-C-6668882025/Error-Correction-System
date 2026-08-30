# 错题元数据系统 v2.1 · Android

把**语法填空**与**完成句子**的错题压缩成一份结构化字段，使任何模型在不接触原题的前提下，
仅凭字段就能输出可执行的诊断报告与背诵材料。

Kotlin + Jetpack Compose + Room，单人使用，移动端优先。

## 一条线

```
原题（唯一数据源：题干 + 答案）
   ↓ 按考点树层级筛选
复习 · 末端     每个末端考点的答案形式 + 该考点下每道题的题眼 → 答案
   ↓ 上一层的输出成为下一层的输入
复习 · 三层 / 两层 / 大类    把下层交出的形态汇总，看这一组共同的形态
```

小方向和大方向不是两个功能，是同一份数据的不同筛选层级，
架在 `truncate(kaodian, depth)` 上，depth 4→1 就是自下而上递归。

**这个应用要答的是「这一类空该填成什么形态」，不是「你错得怎么样」。**
错误率、加权失分、跨卷次数、成熟度门槛、一致率看板、终止条件、维护提议队列
在 v3 里全部删掉了——它们答的是后一个问题。

## 目录

```
app/src/main/kotlin/com/ecs/
├── core/                 纯 Kotlin，不依赖 Android，被 logic-tests 直接编译
│   ├── model/            L0 字段、API 端点与协议、模型清单
│   ├── parse/            错题号解析、选择题剥离
│   ├── tree/             考点树、Top-5 向量检索、层级截断
│   ├── rules/            录入校验、题眼红线、文案禁止项
│   ├── agg/              自下而上的递归分组
│   ├── prompt/           九段提示词：可改正文 + 只读契约
│   ├── report/           复习卡片模板
│   ├── update/           版本比较与 Release 解析
│   └── export/           导出包与 CSV 回读
├── agent/                树生成 / 标注 / 复习判断 / 识别
├── data/                 Room、考点树文件、设置、自动备份、仓库
└── ui/                   录入 / 原题 / 复习 三个页面 + 设置 + 提示词 + 导出
logic-tests/              独立 JVM 构建：编译 core/ 与 agent/ 并跑单元测试
```

## 构建

需要 Android SDK 34 与可访问的 Google Maven。

```bash
./gradlew :app:assembleDebug
```

算法层可以脱离 Android SDK 单独验证：

```bash
./gradlew -p logic-tests test      # 192 个用例
```

`logic-tests` 是独立构建，把 `app/src/main/kotlin/com/ecs/core` 与 `.../agent`
作为源目录直接编译，不是副本，改一处两边同步。

## CI 与发版

`.github/workflows/ci.yml` —— 每次 push 与 PR：跑算法层 192 个用例，构建 debug APK，
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

**签名指纹是硬门禁。** 正式证书的 SHA-256 钉在 `signing/release-cert-sha256.txt`，
自 v4.1.0 起固定为 `c659446f…95e3`。发版流程构建完 APK 就跑
`scripts/verify-apk-cert.sh` 比对，不一致（或本次退回了 debug 密钥）直接失败，
包发不出去——因为指纹一变，所有老用户都得卸载重装，本地数据一起没。

下载到包的人也能自己验，不需要 Android SDK（没有 `apksigner` 时会退回
`scripts/apk_cert_sha256.py`，只用 Python 标准库读 APK Signing Block v2/v3）：

```bash
scripts/verify-apk-cert.sh ecs-4.1.0.apk
```

应用内同样能看：设置 → 检查更新，那里显示已装包的真实指纹以及它是否就是这张固定证书。

## 关键实现取舍

**原题是唯一数据源。** v2 明确不存原题，理由是「让任何模型不接触原题也能诊断」。
v3 推翻了这一条：题干和答案都落库，因为换模型重跑标注时没有原料就什么都做不了。
只存题干与答案，不存选项、不存图。

**form_rule 不由模型生成。** 挂在考点树节点上，标注时系统填充，UI 只读。
同一考点下完全一致是结构保证的。

**标注只给 Top-5 候选。** Agent 每次调用互相独立，全树塞进上下文会让它对边缘节点
注意力衰减，第 50 条和第 300 条给同类题不同路径，分组直接裂开。

**选择题只剥不存。** 单选题的考点与语法填空完全重合，剥掉选项后按填空录入：
正确选项内容进 `answer`，选项本身在识别阶段之后即丢弃。同词根时取原形写进 `given`，
词根对不上就留空——宁可空着，也不写进一个错的原形。

**剥离后推不出形态的题会被挡下来。** Agent 输出 `not_form`，留在原题页等你手动处理，
不强行标注。

**id 与主键分开。** `id` 只含题型+题号+空序，跨卷必然重复；Room 主键用
`uid = "{paper}#{id}"`。

**本地 embedding。** 字符 n-gram 哈希，离线、确定性。它算的是字面重合度而非语义相似度；
够用是因为检索只负责挑 5 个候选，判断在 Agent 手里。

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

**选厂商 + 填一个 Key 就能开始用。** 设置页第一张卡片「厂商与模型」是首次使用的全部：
选一个厂商、填 Key，客户端就去问厂商这个 Key 能用哪些模型（`/models`，两种协议都有），
再给识别（视觉）与判断（文本）两档各自动挑一个——视觉挑便宜的，文本挑强的，
挑不出视觉模型（DeepSeek、MiniMax 这类纯文本厂商）会明说，识别档留在原处不动。
录入页顶部填 Key 也走同一条路径，区别只有一个：那里不会动已经配好并在用另一个厂商的档位，
免得「视觉走智谱、判断走 Anthropic」这种搭配被顺手拆掉。

拉到的清单存在本地，离线打开设置页照样有候选可选；厂商没实现 `/models`、
中转站不通、或者就是想手填 ID，展开「高级」——端点、协议、模型 ID 的手动配置一个没删。
`ModelDiscovery` 里的解析、视觉判定与挑选都是纯函数，在 logic-tests 里有用例。

端点列表里每项也各带一个 Key 输入框，两处是同一份数据，改一处另一处跟着变。
两档任一缺 Key 时，首页会显示「还没填 API Key」并给出直达入口。

预置六个端点（Anthropic、智谱 GLM、Kimi、DeepSeek、MiniMax、本地 / 局域网），URL 与 Key 都可改、不可删。
每个端点有「测试连接」按钮：地址、协议、Key 三者错任一个报错都长得一样，
这个按钮把原始状态码和响应片段直接摆出来。

模型分两档。默认由上面那步自动挑，也可以在「高级」里各自选端点 + 填模型 ID：

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

识别、标注、复习判断、考点树生成需要 API Key。录入页顶部与设置页都能填。
其余功能——原题的增删改查、复习页的分层汇总、导出、备份——全部离线可用。

不识别手写作答：识别错会连带污染题眼和后续全部分析，而这种噪声在聚合后看不出来，
报告依然体面，结论完全跑偏。作答改为直接输入错题号。

## 数据迁移

v3 给记录加了 `stem` 列，Room 从 version 1 升到 2，走的是真迁移
（`data/db/Migrations.kt`），不是 `fallbackToDestructiveMigration`——
后者会把已经录进去的题清空。旧记录的 `stem` 为 null，在原题页显示为「没有题干，点开补」。
