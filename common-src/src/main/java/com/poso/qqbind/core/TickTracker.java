package com.poso.qqbind.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自测 tick 环：在主线程 tick 事件的 START/END 之间取工作耗时，攒成与原版
 * tickTimes 同语义的 100 槽环形缓冲，按 {@code /forge tps} 的公式出 mspt 与 TPS；
 * 另外按 tick 完成时刻算真实速率，使 1.21 的 {@code /tick} 调速不被误报成 20。
 * 数值每 tick 末发布一次，HTTP 线程只读 volatile，不再回主线程取值。
 */
public final class TickTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("qqbind-tick-tracker");

    /** mspt 环长度，与原版 tickTimes 一致（约 5 秒 @20TPS） */
    private static final int WORK_RING = 100;
    /** 真实速率环长度（10 秒 @20TPS） */
    private static final int RATE_RING = 200;
    /** 真实速率的统计窗口 */
    private static final long RATE_WINDOW_NS = 10_000_000_000L;
    /** 超过这个时间没有 tick 结束就视为未测得（卡死/冻结/停机），不拿旧值冒充当前值 */
    private static final long STALE_NS = 2_000_000_000L;
    private static final double TPS_MAX = 20.0;

    private static final long[] workNanos = new long[WORK_RING];
    private static final long[] endStamps = new long[RATE_RING];

    private static int workIdx;
    private static int workFilled;
    private static int rateIdx;
    private static int rateFilled;
    private static long tickStartNanos;

    // 每 tick 末发布，HTTP 线程读这三个 volatile
    private static volatile double publishedMspt = Double.NaN;
    private static volatile double publishedTps = Double.NaN;
    private static volatile double publishedRealTps = Double.NaN;
    private static volatile long lastTickEndNanos;

    private TickTracker() {
    }

    public static void onTickStart() {
        tickStartNanos = System.nanoTime();
    }

    public static void onTickEnd() {
        long now = System.nanoTime();
        long start = tickStartNanos;
        // 没配到 START（事件注册顺序变化、或首 tick）时只统计速率，不写耗时
        if (start != 0 && now > start) {
            workNanos[workIdx] = now - start;
            workIdx = (workIdx + 1) % WORK_RING;
            if (workFilled < WORK_RING) {
                workFilled++;
            }
        }
        endStamps[rateIdx] = now;
        rateIdx = (rateIdx + 1) % RATE_RING;
        if (rateFilled < RATE_RING) {
            rateFilled++;
        }
        lastTickEndNanos = now;
        publish(now);
    }

    private static void publish(long now) {
        double mspt = meanMspt();
        publishedMspt = mspt;
        // /forge tps 口径：mean(全部槽) 换算 ms，再 min(1000/mspt, 20)
        publishedTps = mspt > 0 ? Math.min(TPS_MAX, 1000.0 / mspt) : Double.NaN;
        publishedRealTps = realTickRate(now);
    }

    private static double meanMspt() {
        if (workFilled == 0) {
            return Double.NaN;
        }
        long sum = 0L;
        for (int i = 0; i < workFilled; i++) {
            sum += workNanos[i];
        }
        return (double) sum / workFilled / 1_000_000.0;
    }

    /** 最近 RATE_WINDOW_NS 内的真实 tick 速率；刚开服时用"距今实际时长"作分母 */
    private static double realTickRate(long now) {
        if (rateFilled < 2) {
            return Double.NaN;
        }
        long cutoff = now - RATE_WINDOW_NS;
        int count = 0;
        long first = 0L;
        for (int i = 0; i < rateFilled; i++) {
            long stamp = endStamps[i];
            if (stamp >= cutoff) {
                if (count == 0) {
                    first = stamp;
                } else if (stamp < first) {
                    first = stamp;
                }
                count++;
            }
        }
        if (count < 2) {
            return Double.NaN;
        }
        double elapsedSec = Math.min(RATE_WINDOW_NS, now - first) / 1_000_000_000.0;
        return elapsedSec > 0 ? (count - 1) / elapsedSec : Double.NaN;
    }

    private static boolean isStale() {
        long last = lastTickEndNanos;
        return last == 0 || System.nanoTime() - last > STALE_NS;
    }

    /** 未测得（没数据、或主线程 2 秒以上没走完一个 tick）时返回 NaN，调用方据此省略字段 */
    public static double getTps() {
        return isStale() ? Double.NaN : publishedTps;
    }

    public static double getMspt() {
        return isStale() ? Double.NaN : publishedMspt;
    }

    public static double getRealTps() {
        return isStale() ? Double.NaN : publishedRealTps;
    }

    /** 服务器重启后清历史，避免把上一轮的值当本轮读数 */
    public static void reset() {
        workIdx = 0;
        workFilled = 0;
        rateIdx = 0;
        rateFilled = 0;
        tickStartNanos = 0L;
        lastTickEndNanos = 0L;
        publishedMspt = Double.NaN;
        publishedTps = Double.NaN;
        publishedRealTps = Double.NaN;
        LOGGER.debug("Tick tracker reset");
    }
}
