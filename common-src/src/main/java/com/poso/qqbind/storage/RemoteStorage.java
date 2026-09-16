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
    /** 每日同步时间：5:00 */
    private static final int SYNC_HOUR = 5;
    private static final int SYNC_MINUTE = 0;

    /** 定时同步调度器 */
    private ScheduledExecutorService syncScheduler;

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

        // 2. 判断是否满足同步条件
        if (dataSource == null) {
            LOGGER.warn("数据库连接池不可用，跳过全量同步");
            return;
        }
        if (!"hybrid".equalsIgnoreCase(QQBindConfig.STORAGE_MODE)) {
            LOGGER.info("当前 storageMode={}，仅 hybrid 模式执行全量同步",
                    QQBindConfig.STORAGE_MODE);
            return;
        }

        // 3. 启动时全量同步（合并模式）
        try {
            Map<String, String> allFromDB = getAllFromDatabase();
            if (allFromDB.isEmpty()) {
                int localSize = localFallback.getAll().size();
                LOGGER.warn("数据库无绑定记录，保留本地 JSON（本地有 {} 条）", localSize);
            } else {
                localFallback.mergeAll(allFromDB);
                LOGGER.info("启动同步完成，数据库 {} 条已合并到本地", allFromDB.size());
            }
        } catch (Exception e) {
            LOGGER.error("启动时全量同步失败，将依赖按需查询", e);
        }

        // 4. 启动每日定时同步
        startDailySync();
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
        if (dataSource == null) {
            LOGGER.warn("定时同步：数据库连接池不可用，跳过本次");
            return;
        }
        try {
            Map<String, String> allFromDB = getAllFromDatabase();
            if (allFromDB.isEmpty()) {
                LOGGER.warn("定时同步：数据库无记录，跳过本次（保留本地）");
                return;
            }
            localFallback.mergeAll(allFromDB);
            LOGGER.info("定时同步完成，数据库 {} 条已合并到本地", allFromDB.size());
        } catch (Exception e) {
            LOGGER.error("定时同步失败", e);
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

        // 查远程数据库
        if (dataSource != null) {
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
                // 降级到本地 JSON
                return localFallback.getQQ(gameId);
            }
        } else {
            // 连接池未初始化，降级到本地 JSON
            return localFallback.getQQ(gameId);
        }
    }

    @Override
    public String getGameId(String qq) {
        if (dataSource == null) {
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
            LOGGER.error("Database query failed for qq: {}", qq, e);
        }
        return localFallback.getGameId(qq);
    }

    @Override
    public void save(String qq, String gameId) {
        // 先尝试远程写入
        if (dataSource != null) {
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
                    LOGGER.error("Failed to save binding for {} -> {}", gameId, qq, e);
                }
            }
        }
        // 远程失败或不可用，降级到本地 JSON
        localFallback.save(qq, gameId);
        CacheManager.invalidate(gameId); // 清除缓存，避免不一致
    }

    @Override
    public void remove(String gameId) {
        // 先尝试远程删除
        if (dataSource != null) {
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
        if (dataSource == null) {
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

    /**
     * 关闭连接池（模组卸载时调用）
     */
    public void close() {
        // 停止定时同步
        if (syncScheduler != null && !syncScheduler.isShutdown()) {
            syncScheduler.shutdownNow();
            LOGGER.info("定时同步已停止");
        }
        // 关闭数据库连接池
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool closed.");
        }
    }
}