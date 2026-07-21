# repair-node on Linux（正式支持）

Linux amd64/arm64 是一期正式支持平台。发布作业为阻塞门禁：测试、交叉构建、打包、校验和与公司签名服务提交失败即阻断发布。

## 获取与校验

1. 从 GitLab Release Artifact 下载 `repair-node-linux-amd64` 或 `repair-node-linux-arm64` 的 `.tar.gz` 与 `.sha256`。
2. 使用 `scripts/install-node.sh` 安装：

```bash
sudo ./scripts/install-node.sh \
  --archive ./repair-node-linux-amd64.tar.gz \
  --checksum ./repair-node-linux-amd64.sha256 \
  --dest /usr/local/bin \
  --state-dir /var/lib/repair-node \
  --signature ./repair-node-linux-amd64.tar.gz.sig
```

安装器只做：校验 checksum/签名、复制到绝对路径、创建状态目录、打印 `join` 命令。不会自动注册、不会下载 GitLab Runner、不会改防火墙。

## 状态目录

系统服务默认 `/var/lib/repair-node`（0700）。用户态可改用 `$XDG_STATE_HOME/repair-node`。

## 注册

邀请码由管理台一次性展示。手动执行：

```bash
/usr/local/bin/repair-node join \
  --server https://loop-engine.example.internal \
  --code <one-time-invite> \
  --state-dir /var/lib/repair-node
```

个人节点不持有仓库写 Token；模型密钥仅保存在本机。

## 服务单元

包内含 `repair-node.service` 示例。复制到 systemd 目录并 `enable --now` 前，请确认 `ExecStart` 指向实际安装路径。Runner 由节点在 join/配置应用后自行管理，不由安装器拉取。
