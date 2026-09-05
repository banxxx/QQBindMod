package com.poso.qqbind.server;

/**
 * 静态持有 IServerProvider 实例，供跨平台代码获取服务器对象。
 *
 * @author POSOO
 * @version 1.0
 * @since 1.0
 */
public class ServerProviderHolder {
    private static IServerProvider provider;

    public static void setProvider(IServerProvider provider) {
        ServerProviderHolder.provider = provider;
    }

    public static IServerProvider get() {
        if (provider == null) {
            throw new IllegalStateException("IServerProvider not set");
        }
        return provider;
    }
}