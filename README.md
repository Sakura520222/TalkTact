<p align="center">
  <img src="art/talktact-icon.svg" width="128" height="128" alt="TalkTact 图标" />
</p>
<h1 align="center">TalkTact</h1>
<p align="center">微信 / QQ 聊天里的回复建议，由你决定怎么发。</p>
<p align="center">
  <a href="https://github.com/Sakura520222/TalkTact/releases">发行版本</a> ·
  <a href="docs/QUICKSTART.md">开始使用</a> ·
  <a href="CHANGELOG.md">更新记录</a> ·
  <a href="README.en.md">English</a>
</p>

这是 **Sakura520222 独立维护的 TalkTact Fork**，基于 [shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact)。在保留原版聊天助手能力的基础上，重写 App 页面与聊天浮窗，增加 QQ 接入，让常用操作直接可达。

选一条建议，填入输入框，再决定是否发送。模块不会替你发送消息。

## 这个 Fork 带来了什么

### 重新设计的五个页面

状态、军师、角色、试一试、设置，围绕各自的任务重新组织。界面参考 HyperIsland，采用 Miuix 组件、大标题和分组卡片，支持深浅色、壁纸取色与自选背景。

常用设置直接展示，角色可以搜索，提示词可独立编辑；试聊支持取消、重试与候选改写，未保存的设置有离开保护。

### 底栏与整张浮窗的液态玻璃

背景折射、中央柔焦、柔和高光与渐变亮边，文字和按钮保持清晰。底栏保留跟手形变与弹簧回弹，聊天卡片内部的触摸会被拦截，不会点到下面的消息。

Android 13 及以上的高品质模式提供折射效果；可以选择自动、高、低品质，低品质或能力不足时使用普通卡片。

### 卡片何时打开，由你决定

结果、敏感提示和报错只更新按钮状态，**卡片仅在主动点击时展开**。收起后可以继续看消息，切换会话时会清理旧上下文，旧请求不会覆盖新会话。

### 微信之外，加入 QQ

QQ 接入默认关闭，启用并获得框架作用域后使用**手动识别**。QQ 的角色档案和白名单使用独立会话身份，同名微信聊天也会分开处理。

## 与原版的区别

以下对照本 Fork 基于的 [上游 0.8.12](https://github.com/shibry88-netizen/TalkTact/tree/b4c83d7f0326d1a205f5997283d33b7fc654dab1)；原版已有的能力列在下一节。

| 方面 | 原版 | 当前 Fork 分支 |
| --- | --- | --- |
| App 界面 | 原有五页、功能卡片与折叠入口 | 五页重写，分类设置、角色搜索与直接可见的常用操作 |
| 视觉 | 渐变背景与玻璃拟态卡片 | HyperIsland 风格页面、液态玻璃底栏与整卡聊天浮窗 |
| 宿主 | 微信 | 微信 + 可选 QQ，QQ 始终手动识别 |
| 展开行为 | 敏感提示、错误等状态可触发展开 | 仅主动点击展开，卡片范围内触摸不穿透 |
| 会话状态 | 原有缓存与请求状态管理 | 加强切换、暂停和异步回调校验，隔离微信 / QQ 身份 |
| 发行 | 原项目的发行渠道与签名 | 本 Fork 独立维护、签名与发行，保留原名称和包名 |

## 保留原版的聊天能力

- **回复建议与单条改写**：多种策略的候选、推荐标记，以及“再短点”“更正式”“换个说法”。点回复填入输入框，长按复制。
- **军师与角色档案**：内置和自定义提示词、联系人关系备注，以及可选的本人说话风格提炼。
- **模型与接口**：直通或模型分级，保留 Chat Completions、Responses、Anthropic 与自定义接口适配。
- **隐私与本地能力**：会话白名单、敏感内容检查、离线 OCR、本地代理、配置与角色备份。

聊天上下文会发送到你配置的模型接口。原始图片仅在本机识别，识别出的文字会随上下文发送；启用本地代理后，两套服务商 Key 都留在 App 进程。模块不读取微信 / QQ 数据库。详情见 [隐私说明](publish/PRIVACY.md)。

## 获取版本

[Releases](https://github.com/Sakura520222/TalkTact/releases) 提供已发布版本，[Build 工作流](https://github.com/Sakura520222/TalkTact/actions/workflows/build.yml) 提供开发构建。

**本页介绍 `feature/miuix-liquid-ui` 开发分支。** 五页重写、QQ 接入和新玻璃浮窗尚未包含在现有的 `0.8.12-fork.1` 测试版中，下载时请核对对应版本说明。

本 Fork 与原版同包名、不同签名，不能并存。从原版切换前，先导出备份，再卸载安装并导入。具体步骤见 [开始使用](docs/QUICKSTART.md)。

## 兼容与进展

需要 **Android 12+、arm64**，以及提供 **libxposed Modern API 102** 的模块框架。

2026-10-25 已在 Android 16、微信 8.0.66 与 QQ 9.1.60 上检查浮窗材质和交互。图片页读字失败、强制冷启动时出现过的无响应仍在排查；其他系统、宿主版本和完整 OCR 流程尚未全部验证。

## 文档与反馈

| 你想了解的内容 | 入口 |
| --- | --- |
| 安装、迁移与首次使用 | [开始使用](docs/QUICKSTART.md) |
| 常见问题 | [FAQ](publish/FAQ.md) |
| 数据与隐私边界 | [隐私说明](publish/PRIVACY.md) |
| 构建、签名与维护 | [Fork 文档](docs/FORK.md) · [签名说明](signing/README.md) |
| 版本变化与问题反馈 | [更新记录](CHANGELOG.md) · [本仓库 Issues](https://github.com/Sakura520222/TalkTact/issues) |

## 致谢与许可

感谢原作者 **shibry88-netizen** 及上游贡献者。应用名称、包名与图标设计沿用原项目；顶部使用对应的 SVG 矢量图标。界面设计参考 HyperIsland，并保留所用组件与源码的原有声明。

项目遵循 [GPL-3.0](LICENSE)。分发修改版时，请同时提供对应源码，并区分本 Fork 与原版发行物。
