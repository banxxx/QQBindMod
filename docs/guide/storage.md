# 存储与跨服

QQBindMod 支持三种存储模式，两个平台分别在自己的段里配置，互不影响。

| 模式 | 行为 | 适用场景 |
| --- | --- | --- |
| `local` | 只使用本地文件，各服独立；数据库字段自动忽略，不会连接。 | 单服，或暂时不需要跨服。 |
| `remote` | 只使用中心数据库，不依赖本地 JSON 作为主存储。 | 完全依赖中心库，追求跨服一致性。 |
| `hybrid` | 优先读中心库，同时保留本地文件作为缓存/降级；写入时同时更新。 | 推荐的多服模式，兼顾高可用和跨服共享。 |

新生成的默认模板使用 `local`。需要跨服时再改成 `hybrid` 或 `remote` 并填写真实数据库字段。

## OneBot 跨服

所有需要共享数据的服务器必须：

- 使用相同的 `jdbcUrl`、`dbUser`、`dbPassword`；
- 使用**相同的 `qqGroup`**（用于数据隔离）；
- 把 `onebot.storageMode` 设为 `hybrid` 或 `remote`；
- 完整停启服务器。

示例：

```jsonc
"onebot": {
  "qqGroup": "123456789",
  "storageMode": "hybrid",
  "jdbcUrl": "jdbc:mysql://db.example.com:3306/qqbind_db",
  "dbUser": "qqbind",
  "dbPassword": "replace-with-real-password",
  "cacheTtlSeconds": 60,
  "dbRetryIntervalSeconds": 30,
  "dbMaxRetries": 0
}
```

本地数据文件由 `onebot.dataFilePath` 决定，默认 `qqbind/bindings.json`，相对 `config/` 解析，即 `config/qqbind/bindings.json`。

## QQ 官方跨服

官方段使用同名数据库字段：

```jsonc
"qq_official": {
  "storageMode": "hybrid",
  "jdbcUrl": "jdbc:mysql://db.example.com:3306/qqbind_official",
  "dbUser": "qqbind",
  "dbPassword": "replace-with-real-password",
  "allowCenterSync": true,
  "cacheTtlSeconds": 60,
  "dbRetryIntervalSeconds": 30,
  "dbMaxRetries": 0
}
```

注意：

- `storageMode: local` 时不能把 `allowCenterSync` 设为 `true`。
- 官方 `hybrid` / `remote` 仍会在本地保留镜像状态，中心库负责同步；本地状态目录默认 `qqbind/official`。
- 官方运行期字段（缓存、重试、`allowCenterSync` 等）修改后需要完整停启。

## 数据库要求

- 使用 MySQL / TiDB 兼容的 JDBC 地址，例如 `jdbc:mysql://host:3306/database`。
- 数据库账号需要对应库的读写权限。
- 多服必须能访问同一个数据库地址；不要只在一台机器上使用 `127.0.0.1`。
- `local` 模式下不要以为填了数据库字段就会连接；必须切到 `hybrid` / `remote`。

## 缓存与重试

| 字段 | OneBot | QQ 官方 |
| --- | --- | --- |
| `cacheTtlSeconds` | 内存绑定缓存有效期，默认 60。 | 中心库状态刷新间隔，默认 60；0 表示每次维护都刷新。 |
| `dbRetryIntervalSeconds` | 数据库健康检查/重试间隔，默认 30。 | 维护失败后的首次重试间隔，默认 30；之后按倍数退避，单次最长 300 秒。 |
| `dbMaxRetries` | 最大重试次数，0 表示不限。 | 退避次数上限，0 表示不限。 |

## 本地数据位置

| 平台 | 默认位置 |
| --- | --- |
| OneBot | `config/qqbind/bindings.json`（由 `dataFilePath` 控制） |
| QQ 官方 | `qqbind/official/<environmentId>/<serverId>/<sha256(bindingGroupId)>/`；`local` 模式末尾再追加 `local/` |

官方路径由 `dataDirectory` 控制，默认是工作目录下的 `qqbind/official`，不能指向工作目录之外。

## 安全建议

- 不要把真实 `apiToken`、`consoleToken`、`dbPassword` 提交到 Git。
- 数据库只开放给需要的服务器 IP。
- `httpBindHost` 尽量使用 `127.0.0.1` 或内网地址，不要直接暴露到公网。
- 生产环境修改存储模式、端口或官方运行期参数后，完整停启并检查日志。
