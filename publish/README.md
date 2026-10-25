<p align="center">
  <img src="../art/talktact-icon.svg" width="112" height="112" alt="TalkTact 图标" />
</p>
<h1 align="center">TalkTact</h1>
<p align="center">微信 / QQ 聊天里的回复建议，由你决定怎么发。</p>

这是 [Sakura520222/TalkTact](https://github.com/Sakura520222/TalkTact) 独立维护的 Fork，基于 [shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact)。

## Fork 的变化

- **五页重写**：HyperIsland 风格的大标题与分组卡片，常用设置直接展示，提供角色搜索、提示词编辑与可取消重试的试聊。
- **液态玻璃**：底栏与整张聊天卡片具有背景柔焦、高光和边缘折射，前景保持清晰，触摸不穿透；效果品质可选。
- **主动展开**：新结果、敏感提示和报错不会自动打开卡片。
- **可选 QQ 接入**：默认关闭，获得框架作用域后手动识别，档案与白名单和微信隔离。
- **独立维护与发行**：使用本 Fork 的签名和下载渠道，保留原名称、包名与 GPL-3.0。

候选回复、单条改写、军师提示词、角色档案、模型分级、白名单、离线 OCR 与本地代理来自原版并继续保留。完整对照见 [项目首页](../README.md#与原版的区别)。

## 获取与使用

[发行版本](https://github.com/Sakura520222/TalkTact/releases) · [开发构建](https://github.com/Sakura520222/TalkTact/actions/workflows/build.yml) · [开始使用](../docs/QUICKSTART.md)

[0.9.0 正式版](https://github.com/Sakura520222/TalkTact/releases/tag/v0.9.0) 已包含五页重写、QQ 接入和新玻璃浮窗，早期 `0.8.12-fork.1` 测试版不包含这些改动。Android 需为 12+、arm64，框架需提供 Modern API 102；高品质折射需要 Android 13+。

与原版同包名、不同签名，不能并存；切换前先备份。模块不会自动发送消息，也不读取宿主数据库；分析使用你配置的模型接口。图片页识字与强制冷启动无响应仍在排查，当前进展见 [兼容说明](../README.md#兼容与进展)。

[常见问题](FAQ.md) · [隐私说明](PRIVACY.md) · [更新记录](../CHANGELOG.md) · [问题反馈](https://github.com/Sakura520222/TalkTact/issues)

感谢原作者 shibry88-netizen 与上游贡献者，图标设计沿用原项目。遵循 [GPL-3.0](../LICENSE)，分发修改版时提供对应源码。
