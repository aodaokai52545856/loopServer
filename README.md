# Loop Engine

内网 GitLab Issue 驱动的自动代码修复控制面与节点运行时。

## Prerequisites

- JDK 21 and Maven 3.9
- Node.js 24 LTS, Corepack and pnpm
- Go 1.26
- Docker Compose v2

## Local development

Start PostgreSQL:

    docker compose -f deploy/compose.yml up -d

Start the control plane:

    $env:LOOP_GITLAB_WEBHOOK_TOKEN='local-only'
    mvn -pl control-plane spring-boot:run

Start the management console in a second terminal:

    corepack enable
    pnpm --dir console-web install --frozen-lockfile
    pnpm --dir console-web dev

## Verification

    mvn -pl control-plane test
    pnpm --dir console-web test
    pnpm --dir console-web build
    go -C node-runtime test ./...
    git diff --check

Never commit `.env`, device keys, certificates, GitLab tokens, model keys, task workspaces or generated Artifacts.
