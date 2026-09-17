# 构建、测试、发版

## 构建

需要 Android SDK 34 与可访问的 Google Maven。

```bash
./gradlew :app:assembleDebug
```

## 测试

```bash
./gradlew -p logic-tests test      # 248 个用例，几十秒
```

`logic-tests` 是**独立的 JVM 构建**，把 `app/src/main/kotlin/com/ecs/core` 与 `.../agent`
作为源目录直接编译——不是副本，改一处两边同步。不需要 Android SDK。

能这么做的前提是 `core/` 里不出现任何 `android.*`（见 [architecture.md](architecture.md)）。
所以新写算法时，把纯计算留在 core、把碰 Android API 的那一层薄薄地放在 `data/` 或 `ui/`，
是这个仓库里唯一被强制的分工。

模型调用层也能脱网测：`AgentClient.complete` 是 `open` 的，测试里换掉它就能钉住
重试次数、重试时说了什么、token 预算怎么涨。

覆盖到的几类：字段与状态推导、选择题剥离、板块匹配、压缩的可复现性与体积上界、
两级汇总的输入负载（大方向里不含原题字段）、判重、模型清单解析与合并、
推理档响应的取值与截断识别、并发闸门、图片预算、导出与 zip 往返。

## CI

`.github/workflows/ci.yml`，每次 push 与 PR 两个 job：

- **算法层单元测试** —— 不需要 Android SDK，几十秒出结果，报告作为 artifact 上传；
- **构建 debug APK** —— 验证 Compose 那一半能编译，APK 作为 artifact 上传。

## 发版

`.github/workflows/release.yml`，打 `v*` tag 触发，也可以手动 `workflow_dispatch` 传版本号：

```bash
git tag v4.3.0 && git push origin v4.3.0
```

流程：跑测试 → 准备签名密钥 → `assembleRelease` → 重命名产物 → 读签名指纹 →
**校验指纹** → 写发版说明 → 建 Release 并附上 `ecs-<版本>.apk`。

`versionName` 取自 tag，`versionCode` 取自 workflow 的 run number，都用 `-P` 注入，
不需要改 `build.gradle.kts`。

### 签名

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 的 base64：`base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

**签名指纹是硬门禁。** 正式证书的 SHA-256 钉在 `signing/release-cert-sha256.txt`，
自 v4.1.0 起固定为 `c659446f…95e3`。构建完就跑 `scripts/verify-apk-cert.sh` 比对，
不一致、或本次退回了 debug 密钥，**直接失败，包发不出去**。

理由是 v2.1.0 到 v4.0.1 每一版签名都不同：没配 keystore 时构建会退回 AGP 在构建机上
**现场随机生成**的 debug 密钥，而 CI 每次都是全新虚拟机。Android 拒绝跨签名覆盖安装，
所以那几版之间谁也装不上谁——而在此之前没有任何地方显示过签名指纹，
直到用户装不上才发现。

拿到包的人也能自己验，不需要 Android SDK：

```bash
scripts/verify-apk-cert.sh ecs-4.3.0.apk
```

没有 `apksigner` 时它退回 `scripts/apk_cert_sha256.py`——只用 Python 标准库读
APK Signing Block v2/v3 里的证书 DER。minSdk 26 的包默认不带 v1 签名，
`keytool -jarfile` 读不出来，所以不做 PKCS#7 回退：读不到就报错，
而不是给出一个看起来像指纹的错值。

应用内同样能看：设置 → 检查更新，显示已装包的真实指纹以及它是否就是这张固定证书。

## 检查更新

对比 GitHub 上最新 Release 的版本号，有新版就显示版本号与更新说明，
点一下用系统浏览器打开 APK 直链。**不申请安装权限，不做应用内安装。**
当前版本从 `PackageManager` 读，不走 `BuildConfig`（AGP 8 默认不生成它）。
离线或接口失败时显示原因，不崩。
