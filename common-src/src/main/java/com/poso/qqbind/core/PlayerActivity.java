package com.poso.qqbind.core;

/**
 * 单个玩家的活动记录（登录/退出时间戳）。
 * 用于序列化到 last_activity.json。
 */
public class PlayerActivity {
    /** 玩家名（用于显示，可能不是最新的） */
    public String name;

    /** 最后一次登录时间戳（毫秒），-1 表示从未登录 */
    public long lastLogin;

    /** 最后一次退出时间戳（毫秒），-1 表示从未退出或当前在线 */
    public long lastQuit;

    /** 无参构造（供 Gson 反序列化使用） */
    public PlayerActivity() {
        this.lastLogin = -1;
        this.lastQuit = -1;
    }

    public PlayerActivity(String name, long lastLogin, long lastQuit) {
        this.name = name;
        this.lastLogin = lastLogin;
        this.lastQuit = lastQuit;
    }

    /** 该记录中的最后活动时间（登录/退出中较晚者） */
    public long getLastActive() {
        return Math.max(lastLogin, lastQuit);
    }
}