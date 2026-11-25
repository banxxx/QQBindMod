# 配置文件

## 文件位置与优先级

| 文件 | 何时使用 |
| --- | --- |
| `config/qqbind/qqbind-config.jsonc` | 首选。新安装会生成这个文件。 |
| `config/qqbind/qqbind-config.json` | 旧版路径。只有在 `.jsonc` 不存在时读取。 |

两个文件同时存在时使用 `.jsonc`，并在日志里提示。建议只保留一个。

配置文件是 **JSONC**：支持 `//` 行注释、`/* ... */` 块注释和尾逗号。解析前会先去掉注释和尾逗号，然后仍然执行严格 JSON 校验。字符串里的 `//`（例如 `jdbc:mysql://...`）不会被当成注释。

## 推荐结构

新版配置由「公共顶层字段 + 平台段」组成：

```jsonc
{
  "configVersion": 2,
  "bindingPlatform": "onebot",

  "httpPort": 25566,
  "httpBindHost": "127.0.0.1",
  "apiToken": "replace-with-a-long-random-token",
  "enableWhitelistCheck": true,

  "onebot": {
    "qqGroup": "123456789",
    "serverId": "<SERVER_UUID>",
    "storageMode": "local"
  },

  "qq_official": {
    "environmentId": "<ENVIRONMENT_UUID>",
    "bindingGroupId": "<GROUP_OPENID>",
    "serverId": "<SERVER_UUID>",
    "groupHint": "123456789",
    "storageMode": "local",
    "configurationRevision": "1",
    "createFreshState": false,
    "allowCenterSync": false
  }
}
```

`bindingPlatform` 选择当前生效的平台段。另一个段完全不参与校验，所以可以先保留占位值；切换平台时必须完整停启服务器，不能热切换。

完整模板：

- [OneBot + 双平台段模板](https://github.com/banxxx/QQBindMod/blob/master/examples/qqbind-config.jsonc.example)
- [QQ 官方模板](https://github.com/banxxx/QQBindMod/blob/master/examples/qqbind-config-official.jsonc.example)

## 公共字段

这些字段只能放在文件顶层。放进 `onebot` 或 `qq_official` 段会被拒绝，避免同一个令牌、端口或文案出现两份值。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `configVersion` | integer | 使用平台段时必填 | 固定为 `2`。旧版扁平配置可以省略。 |
| `bindingPlatform` | string | 使用平台段时必填 | `onebot` 或 `qq_official`。 |
| `httpPort` | integer | 否 | HTTP API 端口，默认 `25566`，范围 1–65535。修改后需重启。 |
| `httpBindHost` | string | 否 | 监听地址，默认 `0.0.0.0`。不能为空；建议限制为 `127.0.0.1` 或内网地址。 |
| `apiToken` | string | 是 | 机器人插件访问 API 的 Bearer Token。必须是随机长字符串，不能保留默认占位值，否则 HTTP API 不启动。 |
| `enableWhitelistCheck` | boolean | 否 | 是否拦截未绑定玩家，默认 `true`。 |
| `consoleToken` | string | 否 | `/api/console` 的独立令牌。留空不注册该接口；填写时至少 16 位。 |
| `consoleBannedCommands` | string[] | 否 | 额外禁用的控制台命令名。旧版逗号分隔字符串仍兼容。 |
| `titleTemplate` | string | 否 | 未绑定提示标题。 |
| `subtitleTemplate` | string | 否 | 未绑定提示副标题。 |
| `actionBarTemplate` | string | 否 | 未绑定提示动作栏。 |
| `bindSuccessTitle` | string | 否 | 绑定成功标题。 |
| `bindSuccessSubtitle` | string | 否 | 绑定成功副标题。 |
| `bindSuccessActionBar` | string | 否 | 绑定成功动作栏。 |

## OneBot 段字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `qqGroup` | string | 是 | 真实 QQ 群号。既用于 `{groupHint}` 展示，也用于 OneBot 数据隔离。不能为空。 |
| `groupHint` | string | 否 | 展示用群号。留空时自动使用 `qqGroup`。只影响消息展示，不参与鉴权或数据隔离。 |
| `serverId` | string | 否 | 本进程令牌命名空间。可省略、留空或保留 `<SERVER_UUID>`，模组会在内存里生成短标识；不会写回配置文件。 |
| `storageMode` | string | 否 | `local`、`hybrid` 或 `remote`。新模板默认 `local`。 |
| `dataFilePath` | string | 否 | 高级项，默认模板不展示。默认 `qqbind/bindings.json`。 |
| `jdbcUrl` | string | 非 local 时 | 中心数据库 JDBC 地址。`local` 时自动忽略。 |
| `dbUser` | string | 非 local 时 | 数据库用户名。`local` 时自动忽略。 |
| `dbPassword` | string | 非 local 时 | 数据库密码。`local` 时自动忽略。 |
| `cacheTtlSeconds` | integer | 否 | 内存绑定缓存有效期，默认 `60`，不能小于 0。 |
| `dbRetryIntervalSeconds` | integer | 否 | 数据库健康检查/重试间隔，默认 `30`，至少为 1。 |
| `dbMaxRetries` | integer | 否 | 最大重试次数，默认 `0` 表示不限，不能小于 0。 |

## QQ 官方段字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `environmentId` | string | 是 | 官方环境 UUID，必须与插件侧一致。 |
| `bindingGroupId` | string | 是 | 绑定群的 `group_openid`，不是群号，不能直接展示给玩家。 |
| `groupHint` | string | 建议填写 | 玩家能看到的群号，只填数字。留空时消息回退为“当前官方群”。只用于展示。 |
| `serverId` | string | 是 | 本服 UUID，必须是规范 UUID，并与插件配置一致。 |
| `storageMode` | string | 是 | `local`、`hybrid` 或 `remote`。 |
| `dataDirectory` | string | 否 | 高级项，默认模板不展示。官方状态目录基础路径，默认 `qqbind/official`，必须是工作目录内的相对路径。 |
| `configurationRevision` | string | 是 | 官方配置修订号，通常保持 `"1"`。参与官方作用域和凭证轮换校验。 |
| `createFreshState` | boolean | 否 | 是否重建本地官方状态。默认 `false`。 |
| `allowCenterSync` | boolean | 否 | 是否允许向中心库发布已提交变更。默认 `false`；`local` 模式下不能设为 `true`。 |
| `jdbcUrl` / `dbUser` / `dbPassword` | string | 非 local 时 | 官方中心库连接字段。`local` 时自动忽略。 |
| `cacheTtlSeconds` | integer | 否 | 中心库状态刷新间隔，默认 `60`；`0` 表示每次维护都刷新。 |
| `dbRetryIntervalSeconds` | integer | 否 | 维护失败后的首次重试间隔，默认 `30`；之后按倍数退避，单次最长 300 秒。 |
| `dbMaxRetries` | integer | 否 | 退避次数上限，默认 `0` 表示不限。 |

旧版 `official` 嵌套对象（`configurationRevision`、`createFreshState`、`allowCenterSync`、`database`）仍然可以读取，但新配置请直接把字段放在 `qq_official` 段。

## 模板占位符

两个平台共用同一套消息模板，不需要为官方单独配置群号或指令字段。

| 占位符 | OneBot 模式 | QQ 官方模式 |
| --- | --- | --- |
| `{groupHint}` | `onebot.groupHint`，留空时取 `qqGroup` | `qq_official.groupHint`，留空时显示“当前官方群” |
| `{bindCommand}` | `/绑定 <令牌>` | `/mc bind <绑定码>` |
| `{token}` | 6 位绑定令牌 | 6 位绑定码 |
| `{qqGroup}` | 仅兼容旧模板 | 不建议使用 |

修改 `subtitleTemplate` 里的其它文字不会影响替换：保留 `{groupHint}`，群号就会出现在该位置；删掉占位符，群号才不再显示。`{bindCommand}` 同理。

## local 模式与数据库字段

`storageMode: local` 时，两个平台都只使用本地存储：

- OneBot 使用 `qqbind/bindings.json`。
- QQ 官方使用 `qqbind/official/<environmentId>/<serverId>/<sha256(bindingGroupId)>/`；`local` 模式会在末尾再追加 `local/`。

数据库字段可以原样保留在配置里，但不会被读取、校验语义或用于连接。改成 `hybrid` / `remote` 后才会读取并校验 `jdbcUrl` / `dbUser` / `dbPassword`。

> `local` 只跳过数据库语义和连接，不会跳过 JSON 类型校验。例如把 `jdbcUrl` 写成数字，整份配置仍会被拒绝。

## 严格校验规则

- 允许 JSONC 注释和尾逗号；注释必须正确闭合。
- 新版配置（`configVersion: 2` 或带 `onebot` / `qq_official` 段）顶层出现未知字段会被拒绝，避免拼写错误被静默忽略。
- 旧版扁平配置（没有版本号和平台段）为兼容历史文件会忽略未识别键，但已知字段仍严格校验。
- 平台专属字段不能出现在另一个平台的扁平配置里。例如 OneBot 模式拒绝 `environmentId`、`dataDirectory`、`configurationRevision` 等官方字段；官方模式拒绝 `dataFilePath`、`qqGroup` 等 OneBot 字段。
- 类型必须匹配：数字字段写字符串、布尔字段写 `"true"`、命令列表写数字等都会整份拒绝。
- 任何一项校验失败时，整份配置不应用；当前运行目标、令牌和端口保持原样。

## reload 与重启

| 改动 | 生效方式 |
| --- | --- |
| 消息模板、白名单开关、控制台禁用命令等普通字段 | `/qqbind reload` |
| `httpPort`、`storageMode`、`bindingPlatform` | 完整停启 |
| 官方模式的 `apiToken`、`configurationRevision`、`cacheTtlSeconds`、`dbRetryIntervalSeconds`、`dbMaxRetries`、`dataDirectory`、`createFreshState`、`allowCenterSync` | 完整停启；热重载会以 `RESTART_REQUIRED` 拒绝 |
| 数据库地址/账号/密码 | OneBot 模式建议完整停启；官方模式属于运行期参数，需要完整停启 |

日常切换白名单请走面板/API，而不是手改文件后 reload。

## 配置文件丢失时

- 没有官方痕迹时：启动会自动生成一份 OneBot 默认 `qqbind-config.jsonc`，两个平台段和常用文案已启用，`storageMode` 为 `local`。`apiToken` 仍是默认占位值，填好真实值后 HTTP API 才会启动。
- 存在官方痕迹时（`official-selection.marker`、`qqbind/official` 状态目录，或当前运行目标已是官方）：不会生成可运行的 OneBot 默认配置，而是写回一份 `bindingPlatform=qq_official` 的官方占位模板，HTTP API 保持关闭。请填写真实值后完整停启。
- 占位模板不会把旧内存中的 `apiToken`、`consoleToken` 或数据库密码写回文件。
