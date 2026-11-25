# 常见问题

## HTTP API 没有启动

按顺序检查：

1. `apiToken` 是否仍是默认占位值、为空，或明显过短。默认占位值会让 HTTP API 拒绝启动。
2. `consoleToken` 是否填写了但短于 16 位；这也会导致 HTTP API 拒绝启动。
3. `httpPort` 是否被其他程序占用。
4. `httpBindHost` 是否只监听了 `127.0.0.1`，而请求来自另一台机器。
5. 防火墙/安全组是否放行端口。

服务端日志会说明拒绝启动的原因，但不会打印令牌或密码。

## 配置整份不生效

新版配置（`configVersion: 2` 或带平台段）是严格校验的：

- `configVersion` 必须是数字 `2`；
- `bindingPlatform` 必须是 `onebot` 或 `qq_official`；
- 顶层不能有未知字段；
- 数字字段不能写成字符串，布尔字段不能写成 `"true"`；
- 平台专属字段不能混到另一个平台段；
- `configVersion` 存在但平台段缺失、选中段不是对象等都会整份拒绝。

用 `/qqbind status` 或服务端日志查看加载错误。配置失败时，当前运行目标不会改变，所以服务器可能继续使用上一次成功加载的配置。

旧版扁平配置（没有版本号和平台段）会忽略未识别键，以兼容历史文件；已知字段仍然严格校验。

## 改了配置，reload 后没生效

| 改动 | 生效方式 |
| --- | --- |
| 普通字段（模板、白名单开关、控制台禁用命令等） | `/qqbind reload` |
| `httpPort`、`storageMode`、`bindingPlatform` | 完整停启 |
| 官方 `apiToken`、`configurationRevision`、缓存/重试、`dataDirectory`、`createFreshState`、`allowCenterSync` | 完整停启 |
| 数据库地址/账号/密码 | 建议完整停启；官方模式必须完整停启 |

平台切换不能热重载。切换步骤：先把两个段都填好，再把 `bindingPlatform` 改成目标平台，最后完整停止并启动服务器。

## 玩家看不到群号 / 提示里的群号不对

- OneBot：`onebot.groupHint` 留空时会使用 `onebot.qqGroup`。检查 `qqGroup` 是否是真实群号。
- QQ 官方：`qq_official.groupHint` 只用于展示，填玩家能看到的群号（只填数字）。留空时显示“当前官方群”。
- 消息模板里必须保留 `{groupHint}`；删掉占位符当然就不会显示群号。
- 官方模式不要把 `bindingGroupId` 当成群号；它是 `group_openid`，玩家看不懂。

## 官方模式启动后一直未就绪

检查：

- `environmentId`、`serverId` 是否为规范 UUID；
- `bindingGroupId` 是否为空；
- `storageMode` 与 `allowCenterSync` 是否匹配（`local` 不能配 `allowCenterSync: true`）；
- `configurationRevision` 是否为正整数格式，通常保持 `"1"`；
- 中心库地址、账号、密码是否正确；
- 是否需要把 `allowCenterSync` 设为 `true` 才能发布变更；
- 官方运行期字段是否在修改后完整停启。

## local 模式为什么还保留数据库字段？

这是有意的。默认模板把数据库字段直接写出来，方便以后切到 `hybrid` / `remote`，但 `local` 模式下不会读取、校验语义或连接数据库。只有切到 `hybrid` / `remote` 才会使用这些字段。

注意：`local` 只跳过数据库语义和连接，不跳过 JSON 类型校验。

## 配置文件被删了会怎样？

- 没有官方痕迹：启动时会重新生成 OneBot 默认 `qqbind-config.jsonc`，`apiToken` 是占位值，HTTP API 不会启动；填好真实值后重启即可。
- 有官方痕迹：不会生成可运行的 OneBot 默认配置，而是生成官方恢复模板并保持 HTTP API 关闭，避免官方服静默切回旧身份。

## 数据文件在哪里？

| 平台 | 位置 |
| --- | --- |
| OneBot | `config/qqbind/bindings.json`（由 `onebot.dataFilePath` 控制） |
| QQ 官方 | `qqbind/official/<environmentId>/<serverId>/<sha256(bindingGroupId)>/`（由 `dataDirectory` 控制；`local` 模式末尾再追加 `local/`） |

不要把这些运行数据提交到 Git。

## 多服数据不共享

检查所有服务器：

- `jdbcUrl`、`dbUser`、`dbPassword` 是否相同；
- OneBot 的 `qqGroup` 是否相同；
- `storageMode` 是否都是 `hybrid` 或 `remote`；
- 官方模式的 `environmentId`、`bindingGroupId`、`serverId` 是否在同一作用域；
- 官方模式是否需要 `allowCenterSync: true` 才能发布变更；
- 服务器之间是否能访问数据库，账号是否有读写权限。

## 机器人请求返回 401 / 403

- 401：`apiToken` 与插件配置不一致，或请求头格式不是 `Authorization: Bearer <token>`。
- 403/拒绝：可能访问了官方模式下不开放的旧写接口，或 `/api/console` 没有配置/令牌错误。
- `/api/console` 使用独立的 `consoleToken`，不要拿 `apiToken` 调它。

## 日志里会不会泄露令牌或密码？

模组在配置加载失败时不会打印解析片段、令牌或数据库密码。请不要把完整配置文件或含密钥的日志发到公开 Issue；需要求助时先删除 `apiToken`、`consoleToken`、`dbPassword` 等字段。
