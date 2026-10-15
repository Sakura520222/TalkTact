# TalkTact · 微信聊天助手（Xposed 模块）

在微信聊天页右上角留一个**小按钮**，点开才是候选回复卡片：读最近几条消息 → 调 LLM → 给 3 条风格不同的候选回复 → **点一下填入输入框**（不会自动发送）。默认收起、不挡消息。

本仓库是 [Sakura520222/TalkTact](https://github.com/Sakura520222/TalkTact) 的**独立二改版本**，基于 [shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact)。保留应用名称 **TalkTact**、包名 `io.github.shibry88_netizen.talktact` 及 GPL-3.0，不向上游提交代码，只按需合入高价值更新。

当前源码基线为 **0.8.12**（versionCode 39），不代表已经发布二改安装包。构建、签名和维护方式见 [docs/FORK.md](docs/FORK.md)，历次改动见 [CHANGELOG.md](CHANGELOG.md)。

> English: [README.en.md](README.en.md)

## 兼容的框架

v0.7.0 起本模块改用 **libxposed Modern API 102**（`minApiVersion=102`），因此框架本身必须提供 API 102：

| 框架 | 情况 |
|---|---|
| **LSPosed / Vector** | ✅ 需要框架提供 libxposed API 102。框架版本不够时模块会显示为不兼容、根本不会加载 —— 先升级框架。 |
| **NPatch**（免 root） | ✅ 支持现代 API 102，可直接使用。 |
| **原版 LSPatch** | ❌ 不支持。它 2023-12 就已归档，比 libxposed 现代 API 还早，加载器只认 legacy 的 `assets/xposed_init` —— 对现代模块来说它"根本不认"。请改用 NPatch。 |

> 免 root 路线（NPatch）是"给目标 APK 打补丁"：作用域在打补丁时选，不走模块里的 `scope.list`；
> 而且要**先把微信本身 patch 出来能正常登录**，再谈模块。

> 想在状态页看到「框架已把模块注入本应用」，可以把本模块自己也勾进作用域；不勾也不影响使用
> （状态页会退回到「框架已连上本模块」或「微信进程里跑过本模块」）。

## 功能

- **候选回复卡片** —— 聊天页顶部悬浮；读最近 N 条，给 3 条策略不同的候选（稳妥 / 幽默 / 推进），点一下填入输入框，长按复制。
- **多套 skill** —— 内置「原版狗头军师」「狗头军师·满血版」「程序员搭子」；也能粘一个 GitHub 上 `SKILL.md` 的地址导入，导入时自动补 JSON 输出契约。
- **角色档案** —— 按微信名归档平时聊到的内容（同一内容 1 小时内只留一条），给每个人写「TA 是你什么人」和「平时的关系」；生成时会连同攒下的聊天记录一起拼进提示词，直接影响输出。
- **玻璃拟态界面** —— 渐变背景由界面自己画（不再打包背景图）；卡片半透明、带上光源与右下暗边，圆角 / 间距 / 字号统一成套。「玻璃强度」可调，也能换成你自己的图；选了自己的图之后，面板背后还会有真实的背景模糊（`RenderEffect`）。低内存机型会自动降级（不模糊 / 不折射，只留半透明染色），也能在「设置 → 外观 → 效果品质」手动钉死（自动 / 高 / 低）。
- **★ 最推荐 + 单条改写** —— 三条候选里标出**最推荐**的那条和一句理由（输出契约的 `best` / `why`）；每条右边一个 ✎（**再短点 / 更正式 / 换个说法**），**只改你点的那一条**，另外两条不动。回复超过 60 字会标「· 偏长」。
- **直通 / 模型分级双模式** —— 默认「直通」（一套接口、一次调用）；切成「模型分级」后拆成两路（一路只判意图与风险、一路只写 3 条回复）**并行**跑完再合成同一张卡片。第二套接口留空就复用第一套；**任一路挂了另一路照常显示**（风险那路挂了标「未评估」），不会出现「模型明明回了、卡片却是空的」。
- **本地代理：API Key 不再交给微信进程** —— App 起一个只绑 `127.0.0.1` 的前台服务（随机 token 校验），Key 只留在 App 进程，微信进程只把请求发给本机回环地址，由 App 转发到真正的接口。开关在「高级设置」，打开前也能照旧直连。
- **会话白名单** —— 只让勾过的聊天工作：没加的一律不读内容、不记角色、不调接口；**认不出会话名的按「没勾」处理**（fail-closed）。点「拉取会话列表」→ 回微信首页 / 通讯录停两秒 → 回来勾选即可，打开过的会话也会自动进来；候选只认「像会话的行」（个人资料页不再误判），支持长按多选与「已忽略」名单。
- **图片文字识别（离线 OCR）** —— 聊天里的图片会在**本机**把图上的字认出来，当那条消息的内容送给模型（对方发来的截图、长图文不用你复述）。用 ML Kit 中文离线模型：**不上传、不联网、不落盘**，图片只在微信进程与 App 进程之间经本地回环走一趟；认不出 / 没开代理一律回落成 `[图片/表情/语音]` 占位。代价是安装包变大（≈70MB，模型与原生库占 40MB+）。
- **回答更稳** —— 可选「**严格 JSON 输出**」（只作用在生成候选那条路）；模型没按契约回时**自动补正重试一次**；输出被 `max_tokens` 截断时先**抢救已经说完的那几条**，而不是整条报错。跨请求不变的内容排到最前，吃服务端的**前缀缓存**。
- **诊断包导出** —— 「高级设置 → 备份」可一键导出 `.zip`（环境 / 配置 / 角色条数 / 诊断 / 最近一次调用 / 用量），**含聊天内容、不含 API Key**，方便反馈问题。
- **自检与排查** —— 心跳检测（确认在微信进程里真的生效）、接口自检、敏感内容自检、界面结构诊断，以及把「最近一次调用真正发出去的那一份」回传核对。

## 签名

本 fork 使用自己的固定密钥，和上游发行版签名不同。名称和包名保留，因此不能与原版并存；
切换前先在「设置 → 备份 / 迁移」导出备份，再卸载旧版、安装本 fork 并导入。

本地密钥与口令保存在 Git 忽略的 `keystore/`；发行工作流使用
`FORK_KEYSTORE_BASE64`、`FORK_KEYSTORE_PASSWORD`、`FORK_KEYSTORE_ALIAS` 三个 Secrets，
并对照 [证书指纹](signing/certificate.sha256) 验证实际 APK。
普通 CI 只出临时 debug 包；release 缺少固定签名会失败。
继承的 `signing/key.p12.enc` 属于上游，本 fork 不使用它。
详见 [signing/README.md](signing/README.md)。

## 环境要求

- **Android 12 及以上（API 31+）** —— `minSdk = 31`。
  液态玻璃面板背后那层**真实背景模糊**用的是 `Modifier.blur`（底层是 `RenderEffect`），这个能力从 Android 12 才提供。
  因此不再为低版本保留「不模糊」的降级分支，Android 12 以下直接装不上。
- **需要框架提供 libxposed API 102**：LSPosed / Vector，或免 root 的 NPatch 都可以（见上面的兼容表）。
- 微信 **8.0.78** 上验证通过。

## 构建

使用 **JDK 17 + Gradle Wrapper 9.3.1 + AGP 9.1.1**；Android SDK 安装项及 Android Studio 配置见 [docs/FORK.md](docs/FORK.md)。

```powershell
./gradlew.bat :app:testDebugUnitTest --no-daemon --stacktrace
./gradlew.bat :app:assembleDebug --no-daemon
./gradlew.bat :app:assembleRelease --no-daemon  # 需要本 fork 固定签名
```

Linux / macOS 使用 `./gradlew`。单测不需要手机，但构建仍需要 Android SDK。
APK 位于 `app/build/outputs/apk/{debug,release}/`。
分支 push / PR 会运行测试和 debug 构建；版本标签的发行工作流只在
[本 fork](https://github.com/Sakura520222/TalkTact/releases) 创建草稿 Release，附签名校验和 SHA-256，不覆盖已有发行物。

## 安装

1. 装 APK（包名 `io.github.shibry88_netizen.talktact`）→ LSPosed「模块」里启用「TalkTact」→ **作用域勾选微信** → 强杀微信重开。
2. 打开本 App：「设置」填接口地址（OpenAI 兼容，写到 `/v1`）、API Key、模型。
3. 回首页点「接口自检」——真发一次最小请求，确认地址/Key/模型都对。
4. 到微信里打开一个聊天，右上角会出现「军师」按钮 —— 点一下展开候选回复卡片。

**改配置不需要重启微信**：配置由框架的 remote preferences 实时同步到微信进程。
只有「作用域」这类 LSPosed 层面的改动才需要强杀微信。

## 目录结构

```
app/src/main/java/dev/goutou/wingman/
├── MainActivity.kt / ModuleStatus.kt / HeartbeatReceiver.kt   App 入口、模块探针、心跳
├── config/
│   ├── Config.kt           配置读写（App 与注入代码共用一套 key）
│   └── Roles.kt            角色的归档 / 1 小时查重 / 序列化（纯函数，可单测）
├── llm/
│   ├── Json.kt             手写的最小 JSON（解析 + 生成），核心逻辑因此零依赖
│   ├── Suggestion.kt       模型输出的解析与容错
│   ├── Prompt.kt           内置提示词
│   └── LlmClient.kt        OpenAI 兼容接口（HttpURLConnection，刻意不用 OkHttp）
├── wechat/
│   ├── Snapshot.kt         纯数据中间表示（View 树 → 快照）
│   ├── ChatParser.kt       ★ 快照 → 聊天记录：纯函数，可单测
│   ├── Chrome.kt           时间戳/未读数/群标签等「非聊天内容」规则，两边共用
│   ├── Sensitive.kt        发送前的本地隐私自检
│   ├── ViewReader.kt       ★ 唯一依赖 Android 的部分：View 树 → 快照
│   ├── Overlay.kt          悬浮卡片 + 生命周期
│   └── WeChatHook.kt       Xposed 入口
└── ui/
    ├── Ui.kt               主题 / 背景层 / 液态玻璃组件（GlassSurface 里是真实背景模糊）
    └── Screens.kt          五个页面（运行状态 / 试一试 / 军师 / 角色 / 设置）
app/src/test/java/…         纯逻辑层单元测试（181 个用例）
tools/pack-bundle.py        可选：把源码树重新打包成 v0.1 那种 bundle 格式
```

★ 这两文件的分界就是本版最重要的结构改动：**「读 View」和「判断消息是谁发的」被拆开了**，
后者是纯 Kotlin，可以在电脑上跑单元测试，不用每次都装到手机上肉眼试。

## 工作原理

```
Activity.onResume ──► Panel（每个 Activity 一个）
      │
      └─ 每 900ms（不在聊天页 2.6s）：
           ① 屏幕下半部分找得到 EditText？→ 是聊天页，否则收起来
           ② 读消息列表最后一行算「指纹」，没变就什么都不做
           ③ 变了 → ViewReader 把可见行读成 RowSnapshot（气泡底色有缓存，不重复重画）
           ④ ChatParser 判定方向/昵称/过滤噪音 → 最近 N 条 ChatMsg
           ⑤ 最后一条是对方发的？没被关掉？没在最短间隔内？→ 调 LLM
           ⑥ 渲染卡片；点候选 = 写进输入框；长按 = 复制
```

## 调试

- LSPosed 日志里过滤 `[Goutou]`。
- **「读取」里全是 `[图片/表情/语音]`**：说明消息列表选错了（比如选中了表情/更多功能宫格），
  或者你的微信版本里消息文字不是普通 `TextView`。
  **长按卡片标题** → App 首页会出现「诊断」卡片，复制出来即可定位。选列表的逻辑在 `ViewReader.findList()`。
- **读不到消息**：多半是气泡容器识别失败。到 `ViewReader.bubbleOf()` 里看那三个条件
  （`widthRatio < 0.92`、背景像不像气泡、和页面底色差多少），把日志加上打印实际值即可。
- **认不出图片/表情**：`ViewReader.readRow()` 里头像尺寸范围 `dp(24)..dp(84)`。
- **卡片位置不合适**：`Panel.place()`，正常应该是自动跟着消息列表顶部，不需要调。

## 微信版本适配

| 微信版本 | 状态 |
| :-- | :-- |
| **8.0.78** | ✅ 已实机验证（基准版本） |
| 其它 8.0.x | ⚠️ 未验证，能跑但不保证（群聊行结构在版本间变化较大） |

面向用户的常见问题与隐私说明在 [`publish/`](publish/) 下：[FAQ.md](publish/FAQ.md)、[PRIVACY.md](publish/PRIVACY.md)。

## 已知限制

- 只对单聊可靠；群聊会尽量带出昵称，但微信的群聊行结构在版本间变化较大。
- 依赖微信当前的消息列表控件类型（`AbsListView` / `RecyclerView`），改了就得跟着改。
- API Key 明文存放在本应用私有目录（原因见「设置」页说明），别把 `cfg.xml` 到处备份。
- 本模块只读消息、只填输入框、不自动发送；但仍属于修改微信客户端行为，有风控风险，建议先用小号。
- 「角色」页的记录是**部分历史**：模块只在聊天页可见时读得到屏幕上的那几行，所以你不在场时发生的对话不会被记下来。
- 角色名从聊天页顶部标题认，认不出来会整页跳过（宁可漏记也不记错人）；偶尔认错的话把那一页的「诊断」发出来即可调。
- 玻璃面板背后的模糊是**面板级**的：每块玻璃只对「自己那一块」的背景做一次离屏模糊，代价和面板面积成正比。
  默认渐变背景没有细节可模糊，这时会自动跳过，省一次离屏渲染；配了自定义背景图才看得出效果。

## 反馈与许可

二改问题请反馈到 [本仓库 Issues](https://github.com/Sakura520222/TalkTact/issues)。感谢原作者及上游贡献者；保留 [GPL-3.0](LICENSE)，分发修改版时提供对应源码。
