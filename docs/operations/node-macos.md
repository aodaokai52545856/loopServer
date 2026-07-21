# repair-node on macOS（正式支持）

macOS amd64/arm64 是一期正式支持平台。发布作业为阻塞门禁，与 Linux 相同：`go test`、`CGO_ENABLED=0` 交叉构建、打包、SHA-256、提交公司签名服务。

## 获取与校验

1. 下载 `repair-node-darwin-amd64` 或 `repair-node-darwin-arm64` 的 `.tar.gz` 与 `.sha256`。
2. 安装：

```bash
sudo ./scripts/install-node.sh \
  --archive ./repair-node-darwin-arm64.tar.gz \
  --checksum ./repair-node-darwin-arm64.sha256 \
  --dest /usr/local/bin \
  --state-dir /var/lib/repair-node \
  --signature ./repair-node-darwin-arm64.tar.gz.sig
```

安装器行为与 Linux 相同：校验、复制、创建状态目录、打印 join；不静默注册、不下载 Runner、不改防火墙。

## 状态目录

系统服务默认 `/var/lib/repair-node`。用户服务可用 `$XDG_STATE_HOME/repair-node`。

## 注册

```bash
/usr/local/bin/repair-node join \
  --server https://loop-engine.example.internal \
  --code <one-time-invite> \
  --state-dir /var/lib/repair-node
```

## LaunchAgent 示例

包内含 `com.company.repair-node.plist`。按需安装到 `~/Library/LaunchAgents` 或 `/Library/LaunchDaemons`，并确认 ProgramArguments 指向安装路径。正式支持要求节点能完成注册、心跳、OpenCode、Maven/npm 验证与 Artifact 上传。
