# QQ 官方模式

QQ 官方模式使用 QQ 官方机器人接口，不使用 OneBot 群机器人。它适合已经接入 QQ 开放平台、并且机器人插件支持官方 V2 协议的部署。

::: warning 生产使用前先验证
QQ 官方模式仍在持续验证中。正式上线前，请在测试服完整验证：绑定、解绑、白名单、重启恢复、中心库同步和运营操作。不要直接把未验证的配置用于生产服。
:::

## 配置步骤

1. 把 `bindingPlatform` 改成 `qq_official`。
2. 填写 `qq_official` 段：

```jsonc
{
  "configVersion": 2,
  "bindingPlatform": "qq_official",
  "httpPort": 25566,
  "httpBindHost": "127.0.0.1",
  "apiToken": "replace-with-a-long-random-token",
  "qq_official": {
    "environmentId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "bindingGroupId": "GROUP_OPENID",
    "groupHint": "123456789",
    "serverId": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
    "storageMode": "local",
    "configurationRevision": "1",
    "createFreshState": false,
    "allowCenterSync": false
  }
}
```

3. `environmentId` 和 `serverId` 必须是规范 UUID；占位符 `<ENVIRONMENT_UUID>`、`<SERVER_UUID>` 不能直接使用。`environmentId` 在插件「全局参数 → 生效机器人」复制，`serverId` 在「群与服务器 → 编辑服务器」复制。
4. `bindingGroupId` 是绑定群的 `group_openid`，不是群号，不能拿来展示给玩家。
5. 修改官方运行期字段后完整停启服务器，不能只执行 `/qqbind reload`。

## 关键字段

| 字段 | 说明 |
| --- | --- |
| `environmentId` | QQ 官方环境 UUID，必须与机器人插件配置一致。参与作用域隔离和签名。 |
| `bindingGroupId` | 绑定群的 `group_openid`，参与作用域隔离与鉴权。不是群号。 |
| `groupHint` | 玩家能看到的群号，只填数字。留空时消息回退为“当前官方群”。只用于展示，不参与鉴权或数据隔离。 |
| `serverId` | 本服 UUID，必须与插件配置一致。在插件「群与服务器 → 编辑服务器」查看并复制。不能省略，也不建议在环境之间复用。 |
| `configurationRevision` | 官方配置修订号，通常保持 `"1"`。只在官方中心配置升级、令牌轮换或数据源变更时修改。 |
| `storageMode` | `local`、`hybrid` 或 `remote`。本地/混合/远程的差异见 [存储与跨服](/guide/storage)。 |
| `createFreshState` | 是否重建本地官方状态。设置为 `true` 会让会话处于仅初始化状态；除非明确要重建，否则保持 `false`。 |
| `allowCenterSync` | 是否允许向中心库发布已提交变更。默认 `false`；`storageMode: local` 时不能设为 `true`。 |
| `dataDirectory` | 高级项，默认 `qqbind/official`。必须是工作目录内的相对路径，不能使用绝对路径或 `..` 穿越。 |

## 两个“群号”不要混用

- `bindingGroupId`：真实的绑定群键，类型是 `group_openid`，用于鉴权、作用域和数据隔离。玩家看不懂，不能直接显示。
- `groupHint`：展示用群号，只影响消息里的 `{groupHint}`。填错不会改变绑定目标，但玩家会被引导到错误的群。

## 本地状态与恢复

官方状态保存在：

```text
<dataDirectory>/<environmentId>/<serverId>/<sha256(bindingGroupId)>/
```

`storageMode: local` 时会在末尾再追加 `local/` 子目录；`hybrid` / `remote` 直接使用上面的目录。默认 `dataDirectory` 是 `qqbind/official`。不同环境、服务器、群会自动隔离到不同子目录。

如果主配置丢失，但存在官方痕迹（`official-selection.marker`、`qqbind/official` 状态目录，或当前运行目标已经是官方）：

- 模组不会自动生成可运行的 OneBot 默认配置；
- 会写回一份 `bindingPlatform=qq_official` 的官方占位模板；
- HTTP API 保持关闭，直到你填写真实值并完整停启。

模板里的 `<RESTORE_SERVER_UUID>` 表示“填回原来的 serverId”，不是让你生成一个新的：从插件「群与服务器 → 编辑服务器」复制同一台服务器的 ID。官方段的 `serverId` 不能随机生成，也不要用 OneBot 段的 8 位短标识代替。

这样可以避免官方服在配置丢失后静默切回旧身份。

## 玩家绑定流程

未绑定玩家会看到与 OneBot 共用模板的提示：

- `{groupHint}`：`qq_official.groupHint`
- `{bindCommand}`：默认 `/mc bind <绑定码>`
- `{token}`：6 位绑定码

玩家按提示在官方群发送绑定指令，机器人插件通过官方 V2 接口完成绑定。绑定生效并解除旁观限制后，模组会发送 `bindSuccess*` 成功提示。

> 具体命令前缀和消息发送方式以机器人插件为准；模组只负责生成绑定码和完成服务端状态变更。

## 运行期限制

以下字段属于官方运行期参数，修改后 `/qqbind reload` 会以 `RESTART_REQUIRED` 拒绝，必须完整停启：

- `apiToken`
- `configurationRevision`
- `cacheTtlSeconds`
- `dbRetryIntervalSeconds`
- `dbMaxRetries`
- `dataDirectory`
- `createFreshState`
- `allowCenterSync`

白名单开关请通过面板/API 切换；手改文件后 reload 不会被当作普通配置变更处理。

## 排查

| 现象 | 检查 |
| --- | --- |
| 启动后 HTTP API 不工作 | `apiToken` 是否仍是占位值或为空；官方模式还要求令牌是安全的长字符串。 |
| 配置整份不生效 | `environmentId` / `serverId` 是否为规范 UUID；`bindingGroupId` 是否为空；是否把 OneBot 字段混进了官方段。 |
| 提示里的群号是“当前官方群” | `qq_official.groupHint` 为空。填玩家可见的群号（只填数字）。 |
| 玩家能收到绑定码但绑定失败 | 检查 `environmentId`、`bindingGroupId`、`serverId` 是否与插件侧一致；检查中心库和 `allowCenterSync`。 |
| 改了缓存/重试/修订号但没生效 | 这些是官方运行期参数，需要完整停启，不能只 reload。 |
| 官方状态目录找不到 | 检查 `dataDirectory` 是否相对工作目录，以及是否被 `..`/绝对路径拒绝。 |
