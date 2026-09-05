package com.poso.qqbind.core;

import com.poso.qqbind.storage.DataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 绑定业务逻辑核心类，负责管理 QQ 号与游戏 ID 的绑定关系.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class BindingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BindingManager.class);
    private final DataStorage storage;

    public BindingManager(DataStorage storage) {
        this.storage = storage;
        storage.load();
    }

    /**
     * 绑定 QQ 号与游戏 ID
     */
    public BindResult bind(String qq, String gameId) {
        // 检查 gameId 是否已被绑定
        String existingQQ = storage.getQQ(gameId);
        if (existingQQ != null) {
            return new BindResult(false, "该游戏ID已被绑定 (QQ: " + existingQQ + ")");
        }

        // 检查 qq 是否已绑定其他 ID
        String existingGameId = storage.getGameId(qq);
        if (existingGameId != null) {
            return new BindResult(false, "该QQ已绑定游戏ID: " + existingGameId);
        }

        // 执行绑定
        storage.save(qq, gameId);

        // 执行 whitelist add 命令
        CommandExecutor.addWhitelist(gameId);

        LOGGER.info("Bound QQ {} to game ID {}", qq, gameId);
        return new BindResult(true, "绑定成功！您现在可以登录服务器了。");
    }

    /**
     * 通过游戏 ID 解绑
     */
    public boolean unbindByGameId(String gameId) {
        String qq = storage.getQQ(gameId);
        if (qq == null) {
            return false;
        }

        storage.remove(gameId);
        CommandExecutor.removeWhitelist(gameId);
        LOGGER.info("Unbound game ID {} (QQ: {})", gameId, qq);
        return true;
    }

    /**
     * 通过 QQ 号解绑
     */
    public boolean unbindByQQ(String qq) {
        String gameId = storage.getGameId(qq);
        if (gameId == null) {
            return false;
        }

        storage.remove(gameId);
        CommandExecutor.removeWhitelist(gameId);
        LOGGER.info("Unbound QQ {} (game ID: {})", qq, gameId);
        return true;
    }

    /**
     * 检查游戏 ID 是否已绑定
     */
    public boolean isBound(String gameId) {
        return storage.getQQ(gameId) != null;
    }

    /**
     * 获取游戏 ID 绑定的 QQ 号
     */
    public String getQQ(String gameId) {
        return storage.getQQ(gameId);
    }

    /**
     * 通过 QQ 号获取绑定的游戏 ID
     */
    public String getGameIdByQQ(String qq) {
        return storage.getGameId(qq);
    }

    /**
     * 获取所有绑定数据
     */
    public Map<String, String> getAllBindings() {
        return storage.getAll();
    }

    /**
     * 重新加载数据
     */
    public void reload() {
        storage.load();
        LOGGER.info("Binding data reloaded");
    }

    public static class BindResult {
        public final boolean success;
        public final String message;

        public BindResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}