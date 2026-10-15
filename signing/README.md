# 本 fork 的签名

独立二改使用自己的 PKCS#12 密钥，与原版应用签名不同；应用名称和包名仍与原版相同。
从原版切换时先导出备份，再卸载、安装本 fork 并导入。

- 私钥：被 Git 忽略的 `keystore/talktact.p12`。
- 本地配置：同样被忽略的 `keystore/signing.properties`；字段与环境变量见 [docs/FORK.md](../docs/FORK.md)。
- 公钥证书 SHA-256：[`certificate.sha256`](certificate.sha256)。这不是密钥，可以公开；工作流验证实际 APK 必须匹配它。
- GitHub Secrets：`FORK_KEYSTORE_BASE64`、`FORK_KEYSTORE_PASSWORD`、`FORK_KEYSTORE_ALIAS`。
- 缺少配置的 debug 构建使用临时 debug 签名；release 构建拒绝缺失或不完整的固定签名。

本地和发行工作流必须保留同一把密钥，才能覆盖升级本 fork 的旧版本。不要轮换密钥来解决构建问题。
密钥和口令需在仓库以外安全备份；GitHub Secrets 不能用于下载恢复原始值。

## 上游历史文件

`key.p12.enc` 是从上游继承的加密备份，**不是本 fork 的密钥**；当前工作流不读取它，也不使用
`KEYSTORE_PASSPHRASE` 或上游证书指纹。保留文件仅为历史归属和后续上游差异审查。
不要从提交历史恢复已经公开作废的私钥，不要将明文私钥、口令或本机路径提交进 Git。
