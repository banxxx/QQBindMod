# 更新记录

## 2.0.1 — 2026-11-23（官方登录限制在线切换）

- 新增官方作用域 `POST /api/v2/binding/settings/whitelist`：Token、协议版本与 `environment_id + group_id + server_id` 精确校验后，在线修改 `enableWhitelistCheck`、写回 `qqbind-config.json`，并在服务端主线程重新校验在线玩家；关闭时立即放开已记录的受限玩家，无需重启服务器。
- 该路由刻意不持有官方配置读租约，避免与运行期配置写锁自锁；失败时不重放已成功的配置写入。
- `/api/status` 增加 `whitelist_check_writable=true`，配套插件据此决定是否开放按钮；旧模组没有该字段时插件保持只读。
- 版本号升至 2.0.1。四个加载器仍需用当前源码重新构建后部署。

## 2.0.0 — 2026-11-22（QQ 官方机器人 V2 绑定）

这是模组端的官方机器人兼容大版本。OneBot 绑定与现有命令入口保持不变；QQ 官方 V2 使用独立协议、存储和操作记录，不与旧绑定混用。

### 本次更新

- 新增 QQ 官方 V2 `/api/v2/binding/*` 接口：capabilities 能力握手、bind/check/unbind/sync/operations，固定 `environment_id + group_openid + server_id` 范围，协议版本 2。
- 支持 `hybrid` / `remote` / `local` 三种存储模式；中心两表与操作日志、预占、镜像和副作用原子提交，断库或结果未知时不静默改写、不重放。
- 新增中心 JDBC 适配、只读结构预检、固定主机连接、TLS/超时与权限校验；运行时不自动建表或修复结构。
- 接入管理员解绑、list、operation retry/cancel 和原请求人显式恢复；新增原生白名单与 Profile/UUID 解析桥、游戏线程效果队列、在线限制与恢复。
- 新增官方成员检测只读 `view=active_roster`、广播/控制台独立 consoleToken 与持久防重。
- Fabric 1.20.1、Fabric 1.21.1、Forge 1.20.1、NeoForge 1.21.1 四个加载器完成正式接线；发布前需用当前 2.0.0 源码重新构建并核对最终 Jar。

### 兼容性

- OneBot 绑定、配置和现有数据保持兼容；升级不会自动迁移旧的 OneBot 数据到官方表。
- QQ 官方需要配套插件 2.0.0；插件与模组版本不匹配时官方功能 fail-closed。
- 真实 QQ / Minecraft / 数据库 / 浏览器验收仍需按配套插件交付文档执行。

### 详细开发记录

以下记录按时间倒序，已并入 2.0.0。

#### 开发记录 — 2026-11-20（官方断库回执与成员检测只读支持，未测试）

- 中心GET operations/{id}在BindingApi返回DB_UNAVAILABLE/RESULT_UNKNOWN/SCOPE_MISMATCH时受控查询原TokenApi的本服持久reservation；结构损坏/未授权不回退，本地回执不代表中心终态，不释放预占或授权新增/解绑。
- check支持互斥view=active_roster，使用现有认证/范围和active分页结构；游标绑定模式/用户目标/limit，防止与本人查询混用。HTTP只读参数白名单同步增加view。
- /api/status的binding_ready改为运行时内存状态；不替代capabilities的中心结构/读写核验。
- 按用户要求不运行Java测试或四模块构建；旧开发Jar与SOURCE_HASHES未更新，不包含本轮修改。未启动真实MC/数据库/QQ或读取真实配置/凭据/记录。

#### 开发记录 — 2026-11-19（管理员、完整新授权恢复、写命令及remote/local）

- 四模块原生管理员解绑/list/operation retry/cancel正式接入同一后台事务与来源门禁；权限/连接在游戏线程刷新、提交边界再次核对，原生管理员不能提交QQ用户绑定。
- 官方V2能力按实际名单桥/镜像/结构权限检查；local单文件权威、预占终态/墓碑和镜像/版本副作用原子提交，本服operations/check/bind/unbind与显式新授权恢复可用，拒绝中心sync。remote统一中心两表/断库镜像策略，唯一worker每60秒预算刷新一致性快照。
- 新增官方command受控路由，独立consoleToken、外发前持久DISPATCHED、未知不重发；控制台过滤输出只瞬时回送，不写API日志或状态文件。
- 交付后统一简洁检查：一个小型Java本地多账号/撤销/重启/未决恢复/命令防重smoke与配套Python真实回执互通通过。四个正式模块完整build、适用依赖/重映射通过，Jar新类/Mixin/refmap/目标Java和源码散列核对完成。最新产物在build/qqofficial-development/2026-11-19-complete-development/。
- Gradle宿主21；Forge和Fabric1.20.1产物仍Java17。Forge离线仅跳过FG无超时仓库HEAD前置探针，不关闭TLS；中间超时/构建锁和错误宿主失败均修复后才记成功。没有MC/QQ/真实SQL运行或用户配置/凭据/数据读取，不宣称实机验收。
#### 开发记录 — 2026-11-19（四加载器与终态预占恢复）

- Forge1.20.1/NeoForge1.21.1实际启动安装白名单宿主，分版本Authlib与四模块status/reconcile接入；Forge通过FG公共映射任务和Mixin processor生成refmap/Shadow映射，jar/jarJar均重混淆；NeoForge在TOML注册Mojmap Mixin配置。
- 新增运行时本服RESERVED终态自动收尾，最多4项/批轮转/退避；未决或失联保留，COMMITTED消费，REJECTED/ABORTED作废，绝不后台提交或按重启释放。管理员reconcile只排队刷新和对账，不授予绑定权限。
- 四个真实模块完整Gradle编译/依赖打包成功；开发包/SHA256/源码散列/说明见build/qqofficial-development/2026-11-19-four-loaders/。仅既有token26项关键回归，增强原重启场景，无新增用例；无真实MC/SQL/QQ或凭据读取，原生管理员解绑与完整未决授权处理、remote/local等仍待开发。

#### 阶段记录 — 2026-11-19（Fabric原版白名单接线）

- 新增common-native-src的StoredUserEntry/StoredUserList Mixin窄访问层，直接作用于vanilla登录使用的原名单对象；Fabric1.20.1/1.21.1的SERVER_STARTED安装宿主，现有运行时调度文件/游戏/ACK阶段。原生命令先协调再修改，旧宿主失效后禁止继续写入或ACK。
- Profile使用本服接受的玩家/名单快照；认证模式缺失才在现有单worker调用分版本Authlib仓库。离线/代理模式不伪造UUID，缺失保留待确认；内存更新和最后ACK前再次复核。新增管理员 `/qqbind status`。
- 两个Fabric模块分别完整Gradle构建及shadow/remap打包成功，成品资源与映射已核对，开发Jar/SHA256/说明位于build/qqofficial-development/2026-11-19-native-whitelist/。隔离构建不读真实gradle.properties/用户init/配置/记录，不启动游戏；仅既有关键回归，无新增用例。
- Forge/NeoForge安装、管理员/完整预占恢复、remote/local及实际QQ/MC/SQL验收仍未完成，完整绑定门禁保持关闭。

#### 阶段记录 — 2026-11-19（白名单文件协调器）

##### 共享白名单队列

- 新增共享OfficialWhitelistQueue分阶段模型：解析固定ticket、游戏线程更新内存候选、异步文件确认、按当前scope/revision/profile再次复核、逐项镜像ACK；每批最多4个，在线效果队列保持独立，旧账号/版本不会凭文件序号改变业务结果。
- 新增18项队列测试；完整JDK17/21回归各256项、8组源码检查覆盖25文件、两版MC API共享代码类型检查通过。game memory与profile resolver为合成接口，不是正式Loader/游戏接线。
- Profile/UserCache来源、原生名单内存桥/命令hook、管理员/预占完整恢复、remote/local及四Loader构建仍待完成；WHITELIST与binding_ready继续关闭。

##### 固定版本Profile API证据

- 对Authlib 4.0.43/6.0.54来源和两版MC public bytecode进行隔离审阅。GameProfileCache.get(String)缓存miss会同步调用ProfileRepository并修改/保存缓存；Yggdrasil查询同步分页、带重试/sleep，两版Agent/失败callback参数不同。不能直接将该路径接主线程或宣传有界可取消。
- PlayerList仅公开返回原UserWhiteList实例，相关Map字段private。更安全的Profile来源与同一名单内存Access需先逐版本设计和试点；不得启用猜测反射或当作原版白名单已接。

##### 文件协调器前包记录

- 新增独立OfficialWhitelistFile文件协调器：私有owner快照、显式缺失授权、严格uuid/name流式校验、单写锁/散列、fsync/原子替换及未知结果查询；31项文件测试在JDK17/21通过。
- 这一文件层结果不代表本轮共享pipeline完成；public文件回调与原生名单安装仍未接Loader。早期238/JDK和7组记录是前包历史，当前累计结果见下段/开发记录。

#### 阶段记录 — 2026-11-19（配套插件恢复页）

- 配套插件完成原操作WebUI列表/详情/中止/终态补偿和三个受控框架HTTP，Python346项隔离回归、离线Vue/类型检查/发布构建通过。后台不能代办未决提交或回滚终态，真实认证/浏览器验收仍待用户。
- 本包模组只同步说明并核对两版公共MC白名单ABI，没有改Java/SQL、运行模组/Gradle或重跑既有207项。受控内存/保存桥、异步白名单/Profile、模组管理员/未决对账、remote/local和正式四模块仍未完成；capabilities与binding_ready保持关闭。最新边界见开发记录及插件契约v1.12附录T。

#### 阶段记录 — 2026-11-19（前包）

- 配套插件续接：正式聊天入口、独立中心装配和终态补偿 worker 已接，Python 319 项隔离回归通过。模组本包仅同步说明，没有改 Java 或重跑既有模组测试。原版异步白名单、原生管理员授权/未决预占对账、remote/local、四正式模块和真实验收仍待完成；capabilities 与 binding_ready 不因插件接线而开放。最新边界见开发记录及插件契约 v1.11 附录 S，以下是模组前一包的实现记录。

- S3 游戏侧：新增有界官方取码 worker，验证码与玩家连接/代次绑定，退出、重连、停服和延迟回调不复用旧连接；已有持久预占不因退出释放，中心失联时显示待确认。
- 新增 OfficialEffectQueue 与四加载器 tick 接线：I/O 捕获在线副作用 ticket，唯一游戏线程按最新镜像/revision 执行限制/恢复，I/O 批量 ACK，失败按 2/10/30/60/300 秒退避。原版白名单接口会同步保存文件，继续保持 PENDING/NOT_SUPPORTED，未假报完成。
- 进入限制前记录绑定 scope/玩家 UUID/mode 的 HMAC 标记，当前 active revision 才能恢复；伪造标记不授权。初始快照失败只暂停未知账号取码，不阻断已持久镜像放行和本地在线待办；更换 API Token 后旧模式标记失效，须在用户维护流程核对。
- 新增 7 项取码、7 项内存游戏宿主、3 项镜像和 1 项运行时断库重启用例；JDK 17/21 各 207 项执行与 7 组结构检查通过。Minecraft 1.20.1/1.21.1 公共 API 类型检查通过，新增独立安全脚本可重现；插件 Python 270 项通过。没有运行正式加载器/Gradle/真实 MC、DB 或 QQ；插件具体事件策略仅接到运行时工厂，正式绑定入口/中心依赖、白名单异步适配、完整恢复、remote/local 和实机验收仍未完成。

##### 前阶段记录（以下条目保留当时边界）

- 新增 OfficialHttpHandler，WebServer 注册 V2 后经实际 BindingBootstrap/Session 委托。正文前鉴权、来源租约内再验证、sync 显式发布权限；GET 查询/POST UTF-8 JSON、严格重复键/编码/长度/路径检查、1 MiB 请求/响应、8 KiB 查询与 8 个在途解析/分发限制；不重放丢失响应，不记录原请求/异常原文。
- 新增 26 项内存 HTTP、3 项实际启动桥接执行用例和 1 组路由源码检查，Java 共 189 项执行及 6 组结构检查在 JDK 17/21 各通过；Python 263 项通过。没有真实 HttpServer/网络/DB/MC/Gradle，四加载器未构建；官方发码/策略/MC 动作/恢复 worker 未接，capabilities、bind/unbind/check 和插件 binding_ready 保持关闭。前端/SQL 模板未改，下一包接登录/退出取码 I/O 调度。

- 新增官方配置、真实来源读写租约、单核心会话及原生 BindingBootstrap；四加载器延迟创建 OneBot 存储，官方不走旧绑定/取码/同步/配置写入口。启动/停止/登录/重载门禁已改，初始化模式不鉴权或游戏放行，官方配置缺失不退回旧身份。
- 新增运行时/配置桥接 24 项（执行总数 160）与 5 组源码解析/结构验证，JDK 17/21 各通过；Python 263 项通过。使用真实配置/桥接源码配合明确测试替身，不读真实配置或启动 MC；四正式模块构建及 V2 HTTP/发码/游戏动作/恢复 worker 尚未完成。新增占位配置模板，绑定未开放。

- 新增具体镜像来源验证、Bearer 发布权限与配置修订门禁，私有 Permit/快照单次使用且过期；普通鉴权布尔值不能绕过同步。完整快照在一个只读事务核对绑定/墓碑与固定提交结果，合法中心增量不依赖模组数据库在线。
- 新增 23 项来源/快照用例，合计 136 项独立 Java 测试在 JDK 17/21 各通过；Python 完整 263 项通过。实际来源/配置租约、HTTP/游戏/恢复 worker 和真实 SQL/TLS/权限仍未验收，官方 capabilities 保持未开放。

- 新增 Java 中心绑定/解绑与恢复核心、JDBC 两表事务适配；固定目标/昵称依据/原归属/revision 复核，操作结果和副作用待办原子提交；缺 PolicyGuard 或可写连接明确拒绝，COMMIT 未知按原操作确认、不透明重放。
- 新增 23 项事务模型、13 项假 JDBC 用例，独立测试共 113 项，在 JDK 17/21 各通过；七个共享快照与 Python 一致，Python 完整 253 项回归通过。仅离线核心，实际权限/配置/HTTP/游戏/worker、SQL/TLS/行锁及四加载器未验收，官方功能未开放。

- 新增中心两表结构/会话门禁、跨 Python/Java 的规范意图散列、显式单主机 Connector/J 工厂及只读 CenterInspector。中心记录在用于预占/消费确认前重新核对范围、散列、固定 effects 与分项待办；不自动重连、建表或重放。
- 19 项新增中心用例与既有 58 项合计 77 项，在 JDK 17/21 各通过；插件完整 Python 回归 253 项通过。只使用合成元数据/操作与假 JDBC；Java 写事务、生产配置、HTTP/游戏接线和真实数据库/四加载器仍未完成，官方能力未开放。

- 新增一对多版本化官方镜像与持久副作用核心，bindings/operations/pending_effects 与预占共用一个原子文件；撤销保留 revision，旧快照/增量不能覆盖更高版本。
- 白名单/在线任务以固定 scope/state/revision 捕获，游戏线程执行前只查内存版本，I/O worker 分项落盘 ACK。旧任务被替代，同版本异内容暂停自动覆盖，重启保留未完成任务。
- 独立 Java 测试增至 58 项（26 预占、32 镜像/副作用），JDK 17/21 均通过；插件侧 233 项 Python 回归通过，Java/Python 共用同步回执 fixture。生产 JDBC/配置/游戏动作/HTTP/加载器入口仍待接入，官方兼容未完成实机验收。

- 新增未接运行入口的 QQ 官方独立预占核心，使用原子单文件保存预占/消费/作废状态；验证码仅在内存，一对一代次预占经过重启仍可恢复。
- 沿用 V2 operations 路径返回本服 reservation 与中心可用性，中心失联不伪造 ABSENT；空预占只能在中心明确中止后落作废墓碑，阻止迟到 reserve。
- 新增不经过 Gradle 的安全 Java 检查入口和共享合成协议 fixture。26 项独立测试在 JDK 17/21 均通过，尚不代表 Forge/NeoForge/Fabric 正式构建或 MC 联调完成。
- 未修改生产配置、旧 OneBot 主流程、世界/白名单/绑定数据或线上表；真实 V2 HTTP、中心 JDBC、版本镜像和加载器入口待后续接入。
