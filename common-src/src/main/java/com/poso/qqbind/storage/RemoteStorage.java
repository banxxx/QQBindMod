package com.poso.qqbind.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RemoteStorage implements DataStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteStorage.class);
    private HikariDataSource dataSource;
    private final JsonStorage localFallback;  // 降级到本地 JSON
    private boolean loaded = false;
    /** 数据库当前是否可用（健康检查维护，读写路径据此决定是否降级） */
    private volatile boolean dbAvailable = false;
    /** 连续探测失败次数，用于最大重试次数与退避判断 */
    private volatile int consecutiveFailures = 0;
    /** 每日同步时间：5:00 */
    private static final int SYNC_HOUR = 5;
    private static final int SYNC_MINUTE = 0;

    /** 定时同步调度器 */
    private ScheduledExecutorService syncScheduler;
    /** 数据库健康检查调度器 */
    private ScheduledExecutorService healthScheduler;
    /** DB 恢复且补同步完成后的回调（用于业务层重校验在线玩家） */
    private volatile Runnable onRecoveredCallback;

    public RemoteStorage() {
        this.localFallback = new JsonStorage();
        initConnectionPool();
    }

    /**
     * 初始化 HikariCP 连接池
     */
    private void initConnectionPool() {

        // 尝试手动加载 MySQL 驱动
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOGGER.error("❌ MySQL JDBC Driver NOT found in classpath!", e);
            // 此时后续连接必然失败，但我们可以继续尝试（可能会再次抛出异常）
        }

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(QQBindConfig.JDBC_URL);
            config.setUsername(QQBindConfig.DB_USER);
            config.setPassword(QQBindConfig.DB_PASSWORD);

            // 连接池优化配置
            config.setMaximumPoolSize(10);                // 最大连接数
            config.setMinimumIdle(2);                     // 最小空闲连接
            config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(5)); // 获取连接超时
            config.setIdleTimeout(TimeUnit.MINUTES.toMillis(10));
            config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
            config.setLeakDetectionThreshold(TimeUnit.SECONDS.toMillis(10));
            // <=0 表示跳过启动时的初始连接检查：即使此刻连不上数据库，
            // 池也会正常创建并在后台持续重试，避免 dataSource 永久为 null
            config.setInitializationFailTimeout(-1);

            // TiDB 需要的 SSL 参数已在 JDBC URL 中设置，无需额外配置

            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            // 降级：仅使用本地 JSON
            dataSource = null;
        }
    }

    @Override
    public void load() {
        // 1. 先加载本地 JSON（作为兜底）
        localFallback.load();
        loaded = true;

        // 2. 若连接池从未创建成功（如启动时 DB 不可达），reload 时允许按最新配置重建
        if (dataSource == null || dataSource.isClosed()) {
            initConnectionPool();
        }

        // 3. 初始探测 + 启动健康检查（后台周期性重试连接，不阻塞服务器线程）
        dbAvailable = probeDatabase();
        consecutiveFailures = dbAvailable ? 0 : 1;
        if (!dbAvailable) {
            LOGGER.warn("数据库暂不可用，模组以降级模式运行（绑定数据读本地 JSON），健康检查将每 {} 秒重试一次",
                    Math.max(5, QQBindConfig.DB_RETRY_INTERVAL_SECONDS));
        }
        startHealthCheck();

        // 4. 仅 hybrid 模式在可用时执行启动全量同步，并启用每日定时同步
        if ("hybrid".equalsIgnoreCase(QQBindConfig.STORAGE_MODE)) {
            if (dbAvailable) {
                fullSyncFromDatabase("启动");
            } else {
                LOGGER.warn("跳过启动全量同步：数据库不可用，恢复后将自动补同步");
            }
            startDailySync();
        } else {
            LOGGER.info("当前 storageMode={}，不执行全量同步", QQBindConfig.STORAGE_MODE);
        }
    }

    /**
     * 探测数据库是否可用（拿连接并验证，最多等待数秒）
     */
    private boolean probeDatabase() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 启动数据库健康检查（后台守护线程，周期由 DB_RETRY_INTERVAL_SECONDS 控制）
     */
    private synchronized void startHealthCheck() {
        if (healthScheduler != null && !healthScheduler.isShutdown()) {
            return;
        }
        healthScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QQBind-DbHealth");
            t.setDaemon(true);
            return t;
        });
        int intervalSeconds = Math.max(5, QQBindConfig.DB_RETRY_INTERVAL_SECONDS);
        healthScheduler.scheduleWithFixedDelay(this::healthCheckTask,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("数据库健康检查已启动，间隔 {} 秒", intervalSeconds);
    }

    /**
     * 健康检查任务：维护 dbAvailable 状态；
     * 不可用 → 可用的跳变时执行恢复例程（清缓存 + 补全量同步）。
     */
    private void healthCheckTask() {
        // 必须兜底 Throwable：scheduleWithFixedDelay 的任务一旦抛出未捕获错误
        // （如驱动 NoClassDefFoundError）会静默终止整个重试机制
        try {
            doHealthCheck();
        } catch (Throwable t) {
            LOGGER.error("数据库健康检查任务执行异常（已忽略，下轮继续）", t);
        }
    }

    private void doHealthCheck() {
        if (dbAvailable) {
            // 已可用：作为保活探测，检测连接是否丢失
            if (probeDatabase()) {
                consecutiveFailures = 0;
            } else {
                dbAvailable = false;
                consecutiveFailures = 1;
                LOGGER.warn("数据库连接丢失，进入降级模式（绑定数据改用本地 JSON）");
            }
            return;
        }

        // 不可用：按最大重试次数决定是否退避（DB_MAX_RETRIES<=0 表示不限次数）
        int maxRetries = QQBindConfig.DB_MAX_RETRIES;
        consecutiveFailures++;
        if (maxRetries > 0 && consecutiveFailures > maxRetries && consecutiveFailures % 10 != 0) {
            return; // 到达上限后退避：每 10 个周期才探测一次
        }

        if (!probeDatabase()) {
            LOGGER.warn("数据库重试连接失败（第 {} 次探测）", consecutiveFailures);
            return;
        }
        onDbRecovered();
    }

    /**
     * 恢复例程：数据库从不可用变为可用时调用
     */
    private void onDbRecovered() {
        dbAvailable = true;
        consecutiveFailures = 0;
        // 降级期间可能缓存了"未绑定"的空值，必须全清避免误判新绑定玩家
        CacheManager.clear();
        LOGGER.info("数据库连接已恢复，内存缓存已清空");
        if ("hybrid".equalsIgnoreCase(QQBindConfig.STORAGE_MODE) && loaded) {
            fullSyncFromDatabase("恢复后");
        }
        Runnable callback = onRecoveredCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                LOGGER.warn("恢复回调执行失败（不影响同步结果）: {}", e.getMessage());
            }
        }
    }

    /**
     * 从数据库全量拉取当前群组绑定并合并到本地 JSON（DB 数据优先）
     */
    private void fullSyncFromDatabase(String phase) {
        try {
            Map<String, String> allFromDB = getAllFromDatabase();
            if (allFromDB.isEmpty()) {
                LOGGER.warn("（{}）数据库无绑定记录，保留本地 JSON（本地有 {} 条）",
                        phase, localFallback.getAll().size());
            } else {
                localFallback.mergeAll(allFromDB);
                LOGGER.info("（{}）全量同步完成，数据库 {} 条已合并到本地", phase, allFromDB.size());
            }
        } catch (Exception e) {
            LOGGER.error("（{}）全量同步失败，将依赖按需查询", phase, e);
        }
    }

    /** 启动每日定时同步（每天凌晨 5 点执行一次） */
    private void startDailySync() {
        if (syncScheduler != null && !syncScheduler.isShutdown()) {
            return;  // 已经在运行，避免重复启动
        }
        syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QQBind-DailySync");
            t.setDaemon(true);   // 守护线程，不阻塞服务器关闭
            return t;
        });

        long initialDelayMs = computeInitialDelayToNextSync();
        long periodMs = TimeUnit.DAYS.toMillis(1);

        syncScheduler.scheduleWithFixedDelay(
                this::dailySyncTask,
                initialDelayMs,
                periodMs,
                TimeUnit.MILLISECONDS
        );

        LOGGER.info("定时同步已启动，下次执行将在 {} 毫秒后（约 {} 小时）",
                initialDelayMs, initialDelayMs / 3600000);
    }

    /** 计算到下一次凌晨 5 点的毫秒数 */
    private long computeInitialDelayToNextSync() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(SYNC_HOUR, SYNC_MINUTE);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    /** 每日定时同步任务（合并模式，保留本地独有记录） */
    private void dailySyncTask() {
        try {
            if (!dbAvailable) {
                LOGGER.warn("定时同步：数据库不可用，跳过本次");
                return;
            }
            fullSyncFromDatabase("定时");
        } catch (Throwable t) {
            LOGGER.error("定时同步任务执行异常（已忽略，明日继续）", t);
        }
    }

    /** 从数据库一次性拉取当前群组的所有绑定 */
    private Map<String, String> getAllFromDatabase() throws SQLException {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT game_id, qq FROM bindings WHERE group_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, QQBindConfig.QQ_GROUP);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("game_id"), rs.getString("qq"));
                }
            }
        }
        return result;
    }

    /**
     * 获取数据库连接（从连接池）
     */
    private Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Connection pool not available.");
        }
        return dataSource.getConnection();
    }

    // ---------- 核心 CRUD 方法 ----------

    @Override
    public String getQQ(String gameId) {
        // 先查缓存
        String cachedQQ = CacheManager.get(gameId);
        if (cachedQQ != null) {
            return cachedQQ.isEmpty() ? null : cachedQQ;
        }

        // 查远程数据库（不可用时直接走本地，避免读写线程被连接超时阻塞）
        if (dbAvailable) {
            String sql = "SELECT qq FROM bindings WHERE group_id = ? AND game_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, QQBindConfig.QQ_GROUP);
                stmt.setString(2, gameId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String qq = rs.getString("qq");
                        CacheManager.put(gameId, qq, QQBindConfig.CACHE_TTL_SECONDS);
                        return qq;
                    } else {
                        // 未绑定，缓存空值（用空字符串表示）
                        CacheManager.put(gameId, "", QQBindConfig.CACHE_TTL_SECONDS);
                        return null;
                    }
                }
            } catch (SQLException e) {
                LOGGER.warn("查询 {} 失败，标记数据库不可用并降级到本地 JSON: {}", gameId, e.getMessage());
                dbAvailable = false;
                return localFallback.getQQ(gameId);
            }
        } else {
            // 数据库不可用，降级到本地 JSON
            return localFallback.getQQ(gameId);
        }
    }

    @Override
    public String getGameId(String qq) {
        if (!dbAvailable) {
            return localFallback.getGameId(qq);
        }
        String sql = "SELECT game_id FROM bindings WHERE group_id = ? AND qq = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, QQBindConfig.QQ_GROUP);
            stmt.setString(2, qq);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("game_id");
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database query failed for qq: {}, marking db unavailable", qq, e);
            dbAvailable = false;
        }
        return localFallback.getGameId(qq);
    }

    @Override
    public void save(String qq, String gameId) {
        // 先尝试远程写入
        if (dbAvailable) {
            String sql = "INSERT INTO bindings (group_id, game_id, qq) VALUES (?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, QQBindConfig.QQ_GROUP);
                stmt.setString(2, gameId);
                stmt.setString(3, qq);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    LOGGER.info("Bound {} -> {} via TiDB.", gameId, qq);
                    // 更新缓存
                    CacheManager.put(gameId, qq, QQBindConfig.CACHE_TTL_SECONDS);
                    // 同步更新本地 JSON 作为备份
                    localFallback.save(qq, gameId);
                    return;
                }
            } catch (SQLException e) {
                // 处理唯一键冲突
                if (e.getMessage().contains("Duplicate entry") || e.getSQLState().equals("23000")) {
                    LOGGER.warn("Duplicate binding for gameId: {}", gameId);
                } else {
                    LOGGER.error("Failed to save binding for {} -> {}, marking db unavailable", gameId, qq, e);
                    dbAvailable = false;
                }
            }
        }
        // 远程失败或不可用，降级到本地 JSON（DB 恢复后以数据库数据为准，本条仅本服生效）
        LOGGER.warn("数据库不可用，绑定 {} -> {} 仅写入本地 JSON", gameId, qq);
        localFallback.save(qq, gameId);
        CacheManager.invalidate(gameId); // 清除缓存，避免不一致
    }

    @Override
    public void remove(String gameId) {
        // 先尝试远程删除
        if (dbAvailable) {
            String sql = "DELETE FROM bindings WHERE group_id = ? AND game_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, QQBindConfig.QQ_GROUP);
                stmt.setString(2, gameId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    LOGGER.info("Unbound {} via TiDB.", gameId);
                    CacheManager.invalidate(gameId);
                    localFallback.remove(gameId);
                    return;
                } else {
                    LOGGER.warn("No binding found for gameId: {} to remove.", gameId);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to remove binding for gameId: {}", gameId, e);
            }
        }
        // 远程失败或不可用，降级到本地 JSON
        LOGGER.warn("Remote remove failed, falling back to local JSON for {}", gameId);
        localFallback.remove(gameId);
        CacheManager.invalidate(gameId);
    }

    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>();
        if (!dbAvailable) {
            return localFallback.getAll();
        }
        String sql = "SELECT game_id, qq FROM bindings WHERE group_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, QQBindConfig.QQ_GROUP);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("game_id"), rs.getString("qq"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to list all bindings", e);
            return localFallback.getAll();
        }
        return result;
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }

    /** 数据库当前是否可用（供状态 API 展示） */
    public boolean isDbAvailable() {
        return dbAvailable;
    }

    /** 注册"DB 恢复且补同步完成"回调 */
    public void setOnRecoveredCallback(Runnable callback) {
        this.onRecoveredCallback = callback;
    }

    /**
     * 外部权威绑定结果（插件已直写中心库成功）下行同步：
     * 仅写入本地 JSON 镜像与内存缓存，不触碰数据库，DB 恢复后仍可被全量同步覆盖校正。
     */
    public void syncFromCentral(String qq, String gameId) {
        localFallback.save(qq, gameId);
        CacheManager.put(gameId, qq, QQBindConfig.CACHE_TTL_SECONDS);
        LOGGER.info("下行同步：绑定 {} -> {} 已写入本地镜像（DB 不参与）", gameId, qq);
    }

    /**
     * 外部权威解绑结果（插件已从中心库删除）下行同步：仅删本地镜像与缓存。
     */
    public void syncRemovalFromCentral(String gameId) {
        localFallback.remove(gameId);
        CacheManager.invalidate(gameId);
        LOGGER.info("下行同步：绑定 {} 已从本地镜像移除（DB 不参与）", gameId);
    }

    /**
     * 关闭连接池（模组卸载时调用）
     */
    public void close() {
        // 停止定时同步
        if (syncScheduler != null && !syncScheduler.isShutdown()) {
            syncScheduler.shutdownNow();
            LOGGER.info("定时同步已停止");
        }
        // 停止健康检查
        if (healthScheduler != null && !healthScheduler.isShutdown()) {
            healthScheduler.shutdownNow();
            LOGGER.info("数据库健康检查已停止");
        }
        // 关闭数据库连接池
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool closed.");
        }
    }
}