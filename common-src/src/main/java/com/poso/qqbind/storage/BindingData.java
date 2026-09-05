package com.poso.qqbind.storage;

/**
 * 绑定数据实体类，用于 JSON 序列化/反序列化.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class BindingData {
    private String qq;
    private String gameId;
    private long bindTime;

    public BindingData() {}

    public BindingData(String qq, String gameId) {
        this.qq = qq;
        this.gameId = gameId;
        this.bindTime = System.currentTimeMillis();
    }

    public String getQq() { return qq; }
    public void setQq(String qq) { this.qq = qq; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public long getBindTime() { return bindTime; }
    public void setBindTime(long bindTime) { this.bindTime = bindTime; }
}