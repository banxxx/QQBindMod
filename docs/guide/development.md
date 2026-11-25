# 开发指南

## 代码结构

```text
QQBindMod/
├── common-src/        # 平台无关代码：HTTP API、绑定、存储、配置
├── forge/             # Forge 1.20.1 入口、事件、命令
├── neoforge/          # NeoForge 1.21.1 入口、事件、命令
├── fabric-1.20.1/     # Fabric 1.20.1
├── fabric-1.21.1/     # Fabric 1.21.1
├── examples/          # 当前配置模板
├── tests/             # 回归测试与合成夹具
└── docs/              # 本 VitePress 文档站
```

修改 `common-src` 时不要引入平台特定 API；平台差异放在各自的加载器模块里。

## 构建

需要与目标平台匹配的 JDK：

- Forge 1.20.1：Java 17
- Fabric 1.20.1：Java 17
- NeoForge 1.21.1：Java 21
- Fabric 1.21.1：Java 21

```bash
git clone https://github.com/banxxx/QQBindMod.git
cd QQBindMod

./gradlew :forge:build
./gradlew :neoforge:build
./gradlew :fabric-1.20.1:build
./gradlew :fabric-1.21.1:build
```

JAR 位于各模块的 `build/libs/` 下。

## 调试

- 在 IDEA 中按模块运行 `runServer` 任务（例如 `:forge:runServer`）。
- 默认 HTTP 端口为 `25566`。
- 用 curl 验证 API：

```bash
curl -H "Authorization: Bearer your-token" \
  "http://127.0.0.1:25566/api/check?gameId=test"
```

## 测试

- 修改配置解析、官方运行时、HTTP 适配或存储时，请同时更新对应测试。
- 测试使用合成配置和测试替身，不读取真实服务器配置，也不连接真实数据库。
- 不要在测试或日志里打印真实令牌、密码或玩家数据。

## 文档站

文档使用 VitePress，源码在 `docs/`，配置在 `docs/.vitepress/`。

本地开发：

```bash
npm install
npm run docs:dev
```

构建静态站点：

```bash
npm run docs:build
npm run docs:preview
```

默认站点 base 是 `/QQBindMod/`，对应 GitHub Pages 项目地址；本地开发访问 `http://localhost:5173/QQBindMod/`。如果确实需要用根路径预览，在 PowerShell 中设置：

```powershell
$env:DOCS_BASE="/"; npm run docs:dev
```

推送到 `master` 后，GitHub Actions 会构建并部署到 GitHub Pages。首次使用时需要在仓库 **Settings → Pages** 中把 Source 设为 **GitHub Actions**。

## 提交 PR

- 代码编译通过，配置/API 变化同步更新文档。
- 不要提交 `config/qqbind/`、运行日志、数据库密码、令牌或本机路径相关脚本。
- 不要提交 `node_modules/`、VitePress 缓存或构建产物。
- 功能变化请更新 `CHANGELOG.md`；版本号和更新日志按项目流程单独提交。
