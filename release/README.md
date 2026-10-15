# release/ —— 继承的上游正式版留档

本目录的 APK 与校验文件来自上游，保留历史归属，**不是本 fork 的二改发行包**。
原版签名与本 fork 不同；切换前请导出备份，再卸载重装。

## 当前留档

| 项 | 值 |
| :--- | :--- |
| 版本 | **0.8.12**（versionCode 39） |
| 文件 | `TalkTact-0.8.12.apk`（73,767,069 字节 ≈ 70 MiB） |
| sha256 | `e0718644a854f985474866fcab3c11f5c761e8afa34689aaaa87705e7e34c978` |
| 来源 | 模块仓库 `Xposed-Modules-Repo/io.github.shibry88_netizen.talktact` 的 release `39-0.8.12` 同名附件 |
| 释出日期 | 2026-10-11 |
| 主仓库 release | tag `v0.8.12`（与同版本正式源码 tag 一致） |

## 本 fork 的发行方式

独立二改使用 [publish.yml](../.github/workflows/publish.yml) 在
[Sakura520222/TalkTact](https://github.com/Sakura520222/TalkTact/releases) 创建草稿 Release，
附本 fork 固定签名的 release APK 与 SHA-256；不会写上游模块仓库或覆盖旧附件。
详细配置见 [docs/FORK.md](../docs/FORK.md)。

普通测试 APK 只放在构建目录或 CI artifacts 中，不复制到本目录。未来如需调整留档规则，需作为单独的发行任务处理。
