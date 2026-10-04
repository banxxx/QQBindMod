package com.poso.qqbind.storage;

import java.util.Map;

/**
 * 绑定数据存储接口，定义了绑定关系的持久化和查询方法.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public interface DataStorage {
    /**
     * 加载数据
     */
    void load();

    /**
     * 保存绑定关系
     */
    void save(String qq, String gameId);

    /**
     * 移除绑定
     */
    void remove(String gameId);

    /**
     * 通过游戏 ID 获取 QQ
     */
    String getQQ(String gameId);

    /**
     * 非阻塞获取 QQ：只读内存数据（缓存与本地镜像），绝不发起数据库 IO。
     * 供玩家登录等主线程路径使用，避免 DB 抖动时阻塞 server tick。
     * 默认实现即 {@link #getQQ(String)}（本地存储本身就是纯内存读）。
     */
    default String getQQNonBlocking(String gameId) {
        return getQQ(gameId);
    }

    /**
     * 通过 QQ 获取游戏 ID
     */
    String getGameId(String qq);

    /**
     * 获取所有绑定数据
     */
    Map<String, String> getAll();

    /**
     * 检查数据是否已加载
     */
    boolean isLoaded();
}