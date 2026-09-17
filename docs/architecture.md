# 架构

Kotlin + Jetpack Compose + Room，单人使用的 Android 应用（minSdk 26），没有服务端。
模型调用是唯一的网络依赖，其余全部离线可用。

## 四层

```
app/src/main/kotlin/com/ecs/
├── core/     纯 Kotlin。不 import 任何 android.* —— 这是硬约束
│   ├── model/      ErrorRecord 等字段、API 端点与协议、模型清单与发现
│   ├── parse/      记录 id / 批次、选择题剥离
│   ├── tree/       板块骨架：三大类十九支，写死
│   ├── rules/      判断依据红线、文案禁止项、状态推导
│   ├── agg/        按板块归堆 + 送给模型前的确定性压缩
│   ├── direction/  方向树（小方向与大方向共用一个结构）
│   ├── dup/        判重：哪些是同一道题、该留哪条
│   ├── image/      上传前的图片预算（纯计算，不碰 Bitmap）
│   ├── prompt/     六段提示词：可改正文 + 只读契约
│   ├── update/     版本比较与 Release 解析
│   └── export/     导出包、CSV 回读、zip
├── agent/    模型调用层：AgentClient + 三个任务（识别 / 分析 / 汇总）+ 并发闸门
├── data/     Room、DataStore、方向文件、自动备份、仓库
└── ui/       Compose 三个板块页 + 确认 / 设置 / 提示词 / 导出
```

**依赖方向是单向的**：`ui → data → agent → core`，反向一条都没有。
core 只依赖 kotlinx-serialization 与标准库，agent 额外依赖 OkHttp。

这不是洁癖，是为了 `logic-tests`：它是一个独立的 JVM 构建，把
`app/src/main/kotlin/com/ecs/core` 与 `.../agent` **直接作为源目录编译**（不是副本，
改一处两边同步），于是全部算法在没有 Android SDK 的机器上几十秒就能验完。
一旦 core 里出现一个 `android.content.Context`，这条路就断了。

## 依赖注入

`Container`（`EcsApp.kt`）是手写的：依赖统共十来个，一个容器比引一套框架划算。

它做的唯一一件有技术含量的事，是把「当前该用哪个端点、哪个模型」做成**每次调用时解析**：

```kotlin
val client = AgentClient { role ->      // role = TEXT / VISION
    val (endpointId, modelId) = when (role) { … settings.textEndpoint.first() … }
    AgentClient.Config(settings.endpointById(endpointId) ?: fallbackEndpoint(endpointId), modelId)
}
```

设置页改完模型，下一次调用立即生效，不需要重建任何对象；选中的端点被删掉时也不崩，
而是报一个「未配置的端点『x』」，能看懂。

## 状态怎么流到界面

单个 `AppViewModel`，界面只读它的 `StateFlow`：

| 状态 | 来源 |
|---|---|
| `records` | Room 的 `Flow` → `stateIn`，落库即刷新，没有手动通知 |
| `directions` | `DirectionStore` 的 `StateFlow`，文件读写后更新 |
| `busy` / `message` / `messageBad` | 每个动作的进度与结果 |
| `selected` | 原题页的多选集合，空集合 = 不在多选态 |

所有会发起模型调用或写库的动作都包在 `run(label) { … }` 里，它做三件事：

1. **一次只跑一件事**。已经在忙就直接提示「正在 X，等它结束再点」——
   「点了没反应」是一种坏体验，静默排队是另一种。
2. 异常一律收进 `message` 并标红。失败被静默吞掉会让人以为「没分析」是设计如此，
   而真相是 Key 没填。
3. 结束时清 `busy`。

`messageBad` 单独一档是因为「已录入 12 条，分析 12 条」和
「已录入 12 条，分析 0 条；失败 12 条：未配置 API Key」在界面上长得一模一样。

## 并发

模型调用彼此独立、不共用上下文（这也是分析不互相污染的前提），所以可以并发。
`agent/Batch.kt` 是唯一的并发闸门：

- 默认 4 条同时在跑。填满网络等待，又不至于把中转站打成 429。
- 结果**按入参顺序**返回，单条失败包在 `Result` 里，不带走整批。
- 进度按完成条数回调，所以进度条走到头就是真的跑完了。

串行跑十道题等于把十次网络等待相加——这曾经是「分析很慢」的全部来源。

## 界面结构

底部三个板块：**录入 / 原题 / 复习**。顶栏两个图标：提示词、设置。

确认录入与导出是流程页，进去时隐藏底部导航；设置与提示词不是流程而是随时进出的旋钮，
底部导航留着，顶栏给返回箭头，互跳走同一个 `openPanel`（回退栈里最多一层）。
这一条是修出来的：早先两者是整页 destination，进去之后另外两个板块就「消失」了。
