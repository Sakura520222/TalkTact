# 独立二改维护与构建

本仓库是 [Sakura520222/TalkTact](https://github.com/Sakura520222/TalkTact)，基于
[shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact) 独立维护。
保留原作者归属与 GPL-3.0；仅按需筛选上游有价值的提交，不自动同步或向上游提交 PR。

## 应用身份

- 应用及模块名称：`TalkTact`。
- applicationId / 模块包名：`io.github.shibry88_netizen.talktact`。
- Kotlin namespace：`dev.goutou.wingman`。
- 本 fork 使用自己的固定签名。包名相同，因此不能与原版并存；从原版切换时先在设置中导出备份，卸载原版，安装本 fork 后导入。
- 仓库中的 `release/` APK 与 `signing/key.p12.enc` 是继承的上游历史文件，不是本 fork 的发行物或密钥。

## Android Studio

1. 打开仓库根目录；SDK 路径写入被 Git 忽略的 `local.properties`。
2. 在 **Settings → Build, Execution, Deployment → Build Tools → Gradle** 中使用项目的 **Gradle Wrapper**，版本固定为 **9.3.1**。
3. 将 **Gradle JDK** 设置为 **JDK 17**。IDE 自带的 JBR 可用于运行 IDE，无需改为项目 JDK。
4. SDK 平台与 CI 一致：`platforms;android-37.0`、`build-tools;36.0.0`。
5. Sync Project with Gradle Files。运行配置选择 `app`，构建变体默认 `debug`。

`gradle/gradle-daemon-jvm.properties` 要求构建进程使用已安装的 JDK 17；无需改变其它项目使用的系统 Java。
PowerShell 验证命令：

```powershell
./gradlew.bat --version
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace
./gradlew.bat :app:assembleRelease --no-daemon
$env:ANDROID_HOME = '<本机 Android SDK 目录>'
python .github/scripts/verify_apk.py app/build/outputs/apk/release/app-release.apk --expected-cert signing/certificate.sha256
```

构建工具固定为 **AGP 9.1.1 / Gradle 9.3.1 / 内置 Kotlin 与 Compose 插件 2.2.10 / JDK 17**。
SDK 37.0 的新格式需要 AGP 9.1.1；原来的 AGP 8.12.0 会报找不到 `android-37`，
不能通过忽略警告或降低 compileSdk 修复。依据见 [官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。
AGP 内置 Kotlin，JVM target 跟随 Java 的 17；不再重复应用 `org.jetbrains.kotlin.android`。
Linux / macOS 使用 `./gradlew`。不需要全局安装 Gradle；Wrapper 校验下载包的 SHA-256。
JVM 单测不需要手机，但 Android Gradle 构建和 Robolectric 仍需要 Android SDK 与首次下载依赖。
真机 hook、微信 View 适配、玻璃效果和 OCR 必须另外验证。

## 本地签名

固定密钥位于被忽略的 `keystore/talktact.p12`；密码配置位于同目录的 `signing.properties`：

```properties
KEYSTORE_PATH=../keystore/talktact.p12
KEYSTORE_PASSWORD=<密钥口令>
KEYSTORE_ALIAS=talktact-fork
```

`KEYSTORE_PATH` 相对 `app/` 解析，也支持绝对路径。环境变量 `KEYSTORE_PATH`、
`KEYSTORE_PASSWORD`、`KEYSTORE_ALIAS` 优先于本地文件。只配置一部分会直接报错，避免意外退回其它签名。
没有签名配置时仍可构建 debug；release 必须配置固定签名。公钥证书指纹在
[`signing/certificate.sha256`](../signing/certificate.sha256)，发行工作流必须与它比对。
密钥和口令需另行安全备份，不能依赖 GitHub Secrets 恢复原值；不要提交明文文件或使用历史公开密钥。
旧的 `tools/pack-bundle.py` 文本打包工具也排除 `keystore/`、`local.properties` 与本地索引 / 缓存；
本 fork 的发行源码以 Release 同标签的 Git 归档为准。

## GitHub Actions

`build.yml` 在分支 push、指向 `main` 的 PR 和手动触发时运行：完整单测、debug 构建、
应用名称 / 包名 / ABI / 现代模块元数据检查，以及 `apksigner` 验签。普通 CI 不读取发布密钥，
产物是临时 debug 包，不能作为固定签名的升级包。报告与界面截图作为 artifacts 上传。
第三方 Actions 使用已核对版本的固定提交 SHA，运行于 Node 24 和 GitHub 托管的 Ubuntu runner；更新时需同时核对版本与提交。

仓库所有者可手动运行 **Build** 并勾选 `verify_signing`：先通过完整单测和 debug 构建，
再在独立任务中用仓库 Secrets 还原密钥、构建 release APK 并匹配固定证书指纹。
成功后上传 `TalkTact-signed-<commit>` artifact（含 APK 和 SHA-256），用于验证签名配置，
不会创建标签或 Release。普通 push / PR 不会读取签名 Secrets；两个签名入口共用还原脚本并清理临时密钥。

`publish.yml` **只允许在本 fork 中创建草稿 Release**，目标固定为 `Sakura520222/TalkTact`，
不写上游模块仓库。推送 `v*` 标签或在 `main` 上手动指定已有标签时触发；标签必须位于
`main` 历史中，且等于 `v` + `versionName`。未来二改版本可采用例如 `0.8.12-fork.1` 的版本名，
同时合理递增 `versionCode`；普通维护任务不自动改版本或推标签。

仓库 **Settings → Secrets and variables → Actions** 需要三个 Secrets：

| Secret | 内容 |
| --- | --- |
| `FORK_KEYSTORE_BASE64` | 本 fork PKCS#12 文件的 Base64 |
| `FORK_KEYSTORE_PASSWORD` | 同一密钥的口令 |
| `FORK_KEYSTORE_ALIAS` | 本 fork 的 alias，本地默认为 `talktact-fork` |

使用独立的 `FORK_` 名称，旧的上游工作流不会读取它们。不需要 `MODULE_REPO_TOKEN`、上游密钥或
`KEYSTORE_PASSPHRASE`。工作流使用本仓库的 `GITHUB_TOKEN` 写草稿，缺少签名配置或证书不匹配即失败。
完整单测通过后构建 **release APK**（非 debuggable），再验证签名与现代元数据。
附件名带 `-fork.apk`，附 SHA-256；同 tag 已有 Release 时失败，不覆盖附件或改写旧说明。
草稿审阅后由维护者决定正式发布；Release 的源码归档对应相同标签，分发时保留 GPL-3.0。

本地工作流文件只有在提交并推送到自己的仓库后才会生效。配置 Secrets 不会发布 APK。

## 上游更新

`origin` 用于本 fork 的获取和推送，`upstream` 用于获取上游。可设置
`remote.pushDefault=origin`、`branch.main.pushRemote=origin`，并禁用 `upstream` 的 push URL，
降低误推风险。GitHub CLI 的默认仓库也应为本 fork。

更新前检查工作区，在确认过的本地 `main` 基线上建 `feature/…` 或 `fix/…` 分支。
先 fetch 和审查上游差异，再挑选必要提交；保留二改配置、签名和发布目标，完成回归后再合入自己的 `main`。
不要用自动 merge、强制 reset 或上游发布工作流覆盖二改。
