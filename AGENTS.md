# TalkTact 开发约定

本文件适用于整个仓库。子目录中的 `AGENTS.md` 可补充局部规则；用户在当前任务中的明确要求优先于本文。以实际源码、Gradle 配置和 CI 为准，README 中的版本、文件名及测试数量可能滞后。

## 工作方式

- 默认使用中文沟通，说明改了什么、为什么、如何验证及尚未验证的部分。
- 开始修改前检查 `git status`、当前分支和相关差异，保留用户已有的改动。只处理任务涉及的文件，不顺手重构或批量格式化。
- 先查清受影响的完整链路再修改，尤其是配置同步、会话切换、请求回调、缓存、归档和导入恢复；优先修复根因。
- 使用仓库已有工具和实现，避免为小改动增加依赖或引入另一套配置、网络、日志机制。
- 在用户已授权的范围内完成本地工作；提交、推送、创建 PR、打标签和发布遵从任务授权，不把本地修改描述成已经发布。

## 项目基线与代码位置

这是 Android 微信聊天助手和 libxposed Modern API 模块，App 界面使用 Kotlin / Jetpack Compose，微信内悬浮面板使用 Android View。

| 项目 | 当前配置 / 入口 |
| --- | --- |
| 开发仓库 | `Sakura520222/TalkTact`，`origin` 指向此 fork |
| 上游仓库 | `shibry88-netizen/TalkTact`，`upstream` 用于同步上游 |
| 主分支 | `main` |
| 应用标识 | `io.github.shibry88_netizen.talktact` |
| Kotlin namespace | `dev.goutou.wingman`，与应用标识不同 |
| 平台与 ABI | `minSdk=31`、`targetSdk=34`、`compileSdk=37`；当前只打包 `arm64-v8a` |
| 工具链 | JDK 17、Gradle Wrapper 9.3.1、AGP 9.1.1、内置 Kotlin / Compose 插件 2.2.10；调整时同步核对 CI |
| 框架 | libxposed API 102；API 使用 `compileOnly`，service 使用 `implementation` |

以下源码路径均相对于 `app/src/main/java/dev/goutou/wingman/`：

| 位置 | 职责 |
| --- | --- |
| `WingmanEntry.kt`、`XposedApi.kt` | 现代 Xposed 入口、框架调用、自身探针与微信主进程 hook |
| `MainActivity.kt`、`TalkTactApp.kt`、`HeartbeatReceiver.kt` | App 入口、初始化与注入侧状态回传 |
| `config/Config.kt`、`config/RemoteSync.kt` | 配置 key、本地读写、App 向框架 remote preferences 的同步 |
| `config/Roles.kt`、`config/Backup.kt`、`config/DiagExport.kt` | 角色归档、配置迁移和诊断导出 |
| `wechat/ViewReader.kt`、`wechat/Snapshot.kt`、`wechat/ChatParser.kt` | Android View 读取、快照表示与消息解析；保留读取和解析的分层 |
| `wechat/Overlay.kt`、`wechat/ConversationState.kt`、`wechat/Trace.kt` | 微信悬浮面板、生命周期、会话身份、缓存去重和决策轨迹 |
| `llm/` | 接口形态、请求、提示词、JSON 解析、候选回复及模型分级 |
| `proxy/`、`ocr/`、`wechat/ImageOcr.kt` | App 内回环代理、离线识图及微信侧图片读取 |
| `ui/`、`style/SelfStyle.kt` | Compose 页面、玻璃效果与本人说话风格采集 / 提炼 |

单元测试位于 `app/src/test/java/dev/goutou/wingman/`；构建流程见 `.github/workflows/build.yml`，发布流程见 `.github/workflows/publish.yml`。

## 必须保持的行为

- 候选回复仅在用户点击后填入输入框；长按复制。不得自动点击发送、自动回复或群发，也不得读取微信数据库或扩大到其他应用。
- 微信功能只在 `com.tencent.mm` 主进程运行；保留模块自身进程中的激活探针。hook 失败应保护宿主继续运行，不能阻断原方法或令微信崩溃。
- 白名单启用后，未选中的会话和无法确定名称的会话均应拒绝处理；限制必须覆盖消息读取、角色归档、风格采集、OCR 和模型调用，不能只在 UI 层隐藏。
- 保留敏感内容检测、用户主动放行的范围与敏感消息归档过滤；切换会话、页面或配置后，不得沿用另一个上下文的放行状态。
- 会话切换或页面离开时，旧请求回调不能覆盖新会话的界面、缓存或输入框。网络和 OCR 工作不得阻塞微信主线程；面板暂停 / 销毁时要清理相关任务与回调。
- 同一屏已有结果或缓存时，展开面板应恢复结果；不得因角色档案增长或折叠 / 展开反复请求模型。保留请求去重、最短间隔和旧结果校验。
- 模型分级模式须保留两套接口的选择和独立失败处理；第二套未填写时按既有规则复用，任一路失败不能丢掉另一路的有效结果。单条改写只更新选中的候选。
- OCR 模型只在 App 进程运行，原始图片不上传第三方、不落盘；无法识别或代理不可用时保留占位回退。识别出的文字会随聊天上下文发往用户配置的模型接口，相关说明必须准确。

## 实现与兼容性

- Kotlin 遵循现有风格和官方缩进约定。解析、过滤、序列化和状态判断优先保持为可独立测试的纯 Kotlin 函数。
- 注入微信进程的路径优先只用系统 API 与 Kotlin 标准库；不要引入 OkHttp、通用序列化框架、Compose 或 ML Kit 调用，以免宿主类加载冲突或额外资源开销。
- 新增配置先统一定义 `Keys`，核对 `ConfigData`、`ConfigStore`、`RemoteSync`、注入侧读取、UI 和导入导出。保留旧 key、默认值及缺字段回退；有意不导出的字段应明确说明。
- `ConfigStore.save()` 不应覆盖由独立流程维护的字段，例如代理 token 和风格任务状态；修改配置需审查并发保存及跨进程同步路径。
- 保留 OpenAI Chat Completions、Responses、Anthropic 和自定义接口的既有适配；本地代理目前只支持其既有兼容形态，不能假定所有接口可经代理转发。
- 代理须只监听 `127.0.0.1`，聊天与 OCR 请求必须验证随机 token。代理请求使用代理 token，直连和接口自检使用对应服务商凭据；保留第二套接口的路由标记。健康检查的无鉴权响应不得泄露配置或内容。
- 现代模块元数据位于 `app/src/main/resources/META-INF/xposed/`。必须保留 `module.prop`、`java_init.list`、`scope.list` 及 Gradle 打包规则；入口改名时同步字符串引用。不得混回 legacy `assets/xposed_init` 或 `de.robv.android.xposed` API。
- 应用改名或改包名需一起核对 `applicationId`、`MODULE_PKG`、namespace、Manifest 权限 / 广播 action、scope、自身探针及迁移备份。未要求品牌或包名修改时，不因 fork 自动替换这些身份。
- 保留低性能设备上的玻璃效果降级和用户的效果品质选择。Robolectric 截图不能证明真机模糊 / 折射效果正确。

## 隐私与诊断

- 普通日志和 `Trace` 只记录状态、计数、阶段及失败原因；不记录 API Key、代理 token、完整提示词或聊天正文。异常与网络错误也须避免带出凭据。
- 保留诊断包与普通日志的区别：诊断快照、最近一次请求及模型返回可能含聊天内容，只经用户主动导出并保留提示；诊断导出不得包含密钥。
- 备份只有在用户明确选择包含 API Key 时才可写入凭据。不得将备份、诊断包、真实聊天样本或截图提交到仓库或上传到 issue / PR；回归样本使用合成数据。
- 维持 App 私有存储和 `android:allowBackup="false"`。不要为跨进程读取恢复世界可读配置文件，沿用现代 remote preferences 同步路径。

## 构建与验证

仓库包含固定为 Gradle 9.3.1 的 Wrapper，并校验下载包的 SHA-256；优先用 `gradlew.bat`（Windows）或 `./gradlew`（Linux / macOS），不要求全局安装 Gradle。Gradle JDK 使用 17。Android SDK 路径通过本机环境或未跟踪的 `local.properties` 配置；SDK 安装项以 CI 为准，当前 CI 安装 `platforms;android-37.0` 与 `build-tools;36.0.0`。AGP 9.1.1 才能识别 SDK 37.0 的新格式，不要降级回 8.12.0 或降低 compileSdk。Android Studio 配置见 `docs/FORK.md`。

从仓库根目录执行，以下命令适用于 PowerShell：

```powershell
java -version
./gradlew.bat --version
./gradlew.bat :app:testDebugUnitTest --no-daemon --stacktrace
./gradlew.bat :app:assembleDebug --no-daemon
# 定位问题时按测试类缩小范围，例如：
./gradlew.bat :app:testDebugUnitTest --tests 'dev.goutou.wingman.ConversationStateTest' --no-daemon
```

- 代码变更完成后运行相关回归；交付运行时代码前执行完整 `testDebugUnitTest` 和 `assembleDebug`。仅文档修改检查内容、引用路径和差异即可，无需构建 APK。
- JVM 测试无需手机，但 Android Gradle 构建仍需 SDK；界面测试还依赖 Robolectric。缺少环境时报告具体限制，不通过降低 SDK、删除断言或跳过失败用例制造通过结果。
- 测试应覆盖真实行为和受影响的失败 / 生命周期路径，不为低影响改动堆砌重复测试。解析、白名单、敏感过滤、缓存、代理路由和备份变更优先扩展相应的现有测试类。
- 修改 Compose 页面时运行 `ScreenRenderTest` 并查看 `app/build/screenshots/`；涉及真机玻璃效果、hook、微信 View 结构或 OCR 时补充实际设备验证。没有实机证据时明确说明未验证，并记录设备 / 微信 / 框架版本。
- APK 默认产物为 `app/build/outputs/apk/debug/app-debug.apk`，测试报告位于 `app/build/reports/tests/`。模块入口、Gradle 或打包逻辑变更后，按 CI 检查 APK 内三份现代元数据齐全且无 legacy 入口。
- 签名相关变更用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查实际 APK；不能只检查是否存在 v1 `META-INF/*.RSA` 文件。
- 在 Windows 上保持同一段命令的 PowerShell / Bash 语法一致；不要把 CI 的 Bash 脚本未经转换直接粘进 PowerShell。

## Fork、签名与发布

- 开发分支以确认过的 `main` 基线为起点，功能用 `feature/…`，修复用 `fix/…`；提交信息使用简洁的英文 Conventional Commits。同步上游前检查本地状态，不覆盖二改或重写用户已有提交。
- 本项目为独立二改，不向上游提交代码；只按需筛选上游高价值提交。推送或创建 PR 的目标限定为 `Sakura520222/TalkTact`，`upstream` 只用于获取更新；保留原应用名称和包名。
- 不提交明文签名文件、口令、token 或本机 SDK 路径。保留 `.gitignore` 对 `keystore/`、`*.p12`、`*.jks`、构建目录和 `local.properties` 的排除。
- 缺少稳定签名配置时，本地 debug 构建可使用默认 debug 签名；这不能证明可以覆盖安装上游发行版。不得从历史提交恢复已作废的公开密钥，也不能假定 fork 拥有上游 Secrets 或能解开 `signing/key.p12.enc`。
- fork 正式发行使用本 fork 的固定签名，公钥证书指纹位于 `signing/certificate.sha256`，Secrets 名称为 `FORK_KEYSTORE_BASE64` / `FORK_KEYSTORE_PASSWORD` / `FORK_KEYSTORE_ALIAS`。`publish.yml` 仅在 `Sakura520222/TalkTact` 创建草稿 Release，要求标签属于 `main` 历史且与 versionName 匹配，并校验实际 APK 的指纹；不得恢复向上游模块仓库的写入或覆盖旧附件。继承的 `signing/key.p12.enc` 不用于本 fork。
- 核验仓库 Secrets 时由所有者手动运行 `Build` 并启用 `verify_signing`，只生成带 SHA-256 的签名校验 artifact，不创建标签或 Release；普通 push / PR 的构建不接触签名密钥。
- 仅在用户授权的发布任务中调整版本、推送标签或更新 Release。不要将普通测试 APK 放入 `release/`；该目录保留的上游历史 APK 与本 fork 的签名不同，说明见 `release/README.md`。
- 保留 GPL-3.0 许可证、原作者归属和必要声明。对外分发二改时同步提供对应源码，并清楚区分 fork 与上游发行物。

## 文档与交付

- 用户可见行为、平台要求、配置或发布流程改变时，同步相关 `README.md`、`README.en.md`、`publish/` 文档；变更记录遵循 `CHANGELOG.md` 现有格式，不虚构版本或验证结果。
- 交付前检查差异和工作区状态，说明变更文件、验证命令与结果、未覆盖的实机场景，以及是否提交 / 推送。
- 本文件应随项目实际结构与工具链维护，避免复制过时的版本号、测试数量或不存在的命令。

<!-- CODEGRAPH_START -->
## CodeGraph

仅当仓库根目录存在 `.codegraph/` 时，理解或定位代码应先使用 CodeGraph，再使用文本搜索或读取文件：

- 优先使用 MCP 的 `codegraph_explore`；在查询中指定文件、符号或调用链问题，可获取带行号的源码和调用路径。工具若被延迟加载，先按名称发现该工具。
- Shell 回退：`codegraph explore "符号名或问题"`。
- 不存在 `.codegraph/` 时直接使用 `rg` / `rg --files`；不要为了执行任务自行建立索引。
<!-- CODEGRAPH_END -->
