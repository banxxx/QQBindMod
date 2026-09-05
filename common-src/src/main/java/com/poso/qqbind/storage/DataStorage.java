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