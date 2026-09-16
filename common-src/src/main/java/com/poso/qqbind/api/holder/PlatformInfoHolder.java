package com.poso.qqbind.api.holder;

/**
 * 平台展示字符串的持有者。
 * 各平台在 Mod 初始化时调用 setDisplay() 注入，
 * 公共代码通过 getDisplay() 读取。
 */
public final class PlatformInfoHolder {
    private static String display = "Minecraft";

    private PlatformInfoHolder() {}

    public static void setDisplay(String value) {
        if (value != null && !value.isEmpty()) {
            display = value;
        }
    }

    public static String getDisplay() {
        return display;
    }
}