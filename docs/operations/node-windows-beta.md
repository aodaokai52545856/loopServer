# repair-node on Windows（Beta）

Windows amd64 在一期标记为 **Beta**，不是正式支持平台。CI 作业名为 `repair-node-windows-amd64-beta`，Artifact 带 `-beta` 后缀，且 `allow_failure: true`：交叉构建或 smoke 失败不得被重新标注为正式支持。

## 为何是 Beta

在 Windows Service 兼容性与本机 AI/模型工具会话完成认证之前，本地模型工具需要**交互式用户会话**。不要把成功的 cross-build 当成 GA。

## 获取与校验

1. 下载 `repair-node-windows-amd64-beta.tar.gz` 与对应 `.sha256`。
2. 使用 PowerShell 安装器：

```powershell
.\scripts\install-node.ps1 `
  -Archive .\repair-node-windows-amd64-beta.tar.gz `
  -Checksum .\repair-node-windows-amd64-beta.sha256 `
  -Dest 'C:\Program Files\repair-node' `
  -StateDir 'C:\ProgramData\repair-node' `
  -Signature .\repair-node-windows-amd64-beta.tar.gz.sig
```

安装器只校验 checksum/签名、复制到绝对路径、创建状态目录并打印 join；不会自动注册、不会下载 Runner、不会改防火墙。

## 注册（手动）

```powershell
& 'C:\Program Files\repair-node\repair-node.exe' join `
  --server https://loop-engine.example.internal `
  --code <one-time-invite> `
  --state-dir 'C:\ProgramData\repair-node'
```

## 运行建议

- 优先在登录用户会话中运行节点与本地模型工具。
- 服务模式与自动启动在认证完成前仅供试验；失败时保持 Beta 标签并记录证据。
- 个人节点仍不得持有仓库写 Token。
