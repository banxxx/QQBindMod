package com.poso.qqbind.utils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 玩家统计工具类 - 收集 Minecraft 原版 CUSTOM 统计
 * 通过 {@link net.minecraft.stats.Stats} 类提供的常量获取各项统计值
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class StatUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatUtils.class);

    /**
     * 从在线玩家对象获取统计数据
     * @param player 玩家
     */
    public static Map<String, Object> getPlayerStats(ServerPlayer player) {
        Map<String, Object> stats = buildStats(
                player.getName().getString(),
                player.getUUID().toString(),
                statKey -> player.getStats().getValue(Stats.CUSTOM, statKey)
        );
        stats.put("online", true);
        return stats;
    }

    /**
     * 从离线统计数据 Map 构建统计结果
     * @param playerName 玩家名
     * @param uuid 玩家 UUID 字符串
     */
    public static Map<String, Object> getPlayerStatsFromMap(String playerName, String uuid, Map<String, Integer> customStats) {
        Map<String, Object> stats = buildStats(
                playerName,
                uuid,
                statKey -> customStats.getOrDefault(statKey.toString(), 0)
        );
        stats.put("online", false);
        return stats;
    }

    /**
     * 公共构建逻辑，通过 Function 提供每个统计项的取值方式
     */
    public static Map<String, Object> buildStats(String playerName, String uuidStr, Function<ResourceLocation, Integer> provider) {
        Map<String, Object> stats = new HashMap<>();

        try {
            // ---- 基础信息 ----
            stats.put("playerName", playerName);
            stats.put("uuid", uuidStr);
            stats.put("online", true);

            // ---- 所有 CUSTOM 统计（按类别分组） ----

            // 1. 时间相关
            stats.put("leaveGame", provider.apply(Stats.LEAVE_GAME));                     // 离开游戏次数
            stats.put("playTime", provider.apply(Stats.PLAY_TIME));                       // 游玩总时间（tick）
            stats.put("totalWorldTime", provider.apply(Stats.TOTAL_WORLD_TIME));          // 世界总时间（tick）
            stats.put("timeSinceDeath", provider.apply(Stats.TIME_SINCE_DEATH));          // 距离上次死亡时间（tick）
            stats.put("timeSinceRest", provider.apply(Stats.TIME_SINCE_REST));            // 距离上次休息时间（tick）
            stats.put("sneakTime", provider.apply(Stats.CROUCH_TIME));                    // 潜行时间（tick）

            // 2. 移动距离（单位：厘米）
            stats.put("walkDistance", provider.apply(Stats.WALK_ONE_CM) / 100.0);         // 步行距离（米）
            stats.put("crouchDistance", provider.apply(Stats.CROUCH_ONE_CM) / 100.0);     // 潜行移动距离（米）
            stats.put("sprintDistance", provider.apply(Stats.SPRINT_ONE_CM) / 100.0);     // 疾跑距离（米）
            stats.put("walkOnWaterDistance", provider.apply(Stats.WALK_ON_WATER_ONE_CM) / 100.0); // 水上行走距离（米）
            stats.put("fallDistance", provider.apply(Stats.FALL_ONE_CM) / 100.0);         // 掉落距离（米）
            stats.put("climbDistance", provider.apply(Stats.CLIMB_ONE_CM) / 100.0);       // 攀爬距离（米）
            stats.put("flyDistance", provider.apply(Stats.FLY_ONE_CM) / 100.0);           // 飞行距离（米）
            stats.put("walkUnderWaterDistance", provider.apply(Stats.WALK_UNDER_WATER_ONE_CM) / 100.0); // 水下行走距离（米）
            stats.put("minecartDistance", provider.apply(Stats.MINECART_ONE_CM) / 100.0); // 矿车移动距离（米）
            stats.put("boatDistance", provider.apply(Stats.BOAT_ONE_CM) / 100.0);         // 划船距离（米）
            stats.put("pigDistance", provider.apply(Stats.PIG_ONE_CM) / 100.0);           // 骑猪距离（米）
            stats.put("horseDistance", provider.apply(Stats.HORSE_ONE_CM) / 100.0);       // 骑马距离（米）
            stats.put("elytraDistance", provider.apply(Stats.AVIATE_ONE_CM) / 100.0);     // 鞘翅滑行距离（米）
            stats.put("swimDistance", provider.apply(Stats.SWIM_ONE_CM) / 100.0);         // 游泳距离（米）
            stats.put("striderDistance", provider.apply(Stats.STRIDER_ONE_CM) / 100.0);   // 骑炽足兽距离（米）

            // 3. 动作次数
            stats.put("jump", provider.apply(Stats.JUMP));                               // 跳跃次数
            stats.put("drop", provider.apply(Stats.DROP));                               // 丢弃物品次数
            stats.put("damageDealt", provider.apply(Stats.DAMAGE_DEALT));                // 造成伤害（数值，原版除以10）
            stats.put("damageDealtAbsorbed", provider.apply(Stats.DAMAGE_DEALT_ABSORBED)); // 被吸收的伤害（数值）
            stats.put("damageDealtResisted", provider.apply(Stats.DAMAGE_DEALT_RESISTED)); // 被抵抗的伤害（数值）
            stats.put("damageTaken", provider.apply(Stats.DAMAGE_TAKEN));                // 承受伤害（数值）
            stats.put("damageBlockedByShield", provider.apply(Stats.DAMAGE_BLOCKED_BY_SHIELD)); // 盾牌格挡伤害（数值）
            stats.put("damageAbsorbed", provider.apply(Stats.DAMAGE_ABSORBED));          // 吸收伤害（数值）
            stats.put("damageResisted", provider.apply(Stats.DAMAGE_RESISTED));          // 抵抗伤害（数值）
            stats.put("deaths", provider.apply(Stats.DEATHS));                           // 死亡次数
            stats.put("mobKills", provider.apply(Stats.MOB_KILLS));                      // 击杀生物数
            stats.put("animalsBred", provider.apply(Stats.ANIMALS_BRED));                // 繁殖动物数
            stats.put("playerKills", provider.apply(Stats.PLAYER_KILLS));                // 击杀玩家数
            stats.put("fishCaught", provider.apply(Stats.FISH_CAUGHT));                  // 钓鱼次数

            // 4. 与村民/生物交互
            stats.put("talkedToVillager", provider.apply(Stats.TALKED_TO_VILLAGER));      // 与村民对话次数
            stats.put("tradedWithVillager", provider.apply(Stats.TRADED_WITH_VILLAGER));  // 与村民交易次数

            // 5. 方块交互（具体操作）
            stats.put("eatCakeSlice", provider.apply(Stats.EAT_CAKE_SLICE));              // 吃蛋糕片数
            stats.put("fillCauldron", provider.apply(Stats.FILL_CAULDRON));              // 填充炼药锅次数
            stats.put("useCauldron", provider.apply(Stats.USE_CAULDRON));                // 使用炼药锅次数
            stats.put("cleanArmor", provider.apply(Stats.CLEAN_ARMOR));                  // 清洗盔甲次数
            stats.put("cleanBanner", provider.apply(Stats.CLEAN_BANNER));                // 清洗旗帜次数
            stats.put("cleanShulkerBox", provider.apply(Stats.CLEAN_SHULKER_BOX));        // 清洗潜影盒次数
            stats.put("interactWithBrewingStand", provider.apply(Stats.INTERACT_WITH_BREWINGSTAND)); // 与酿造台交互次数
            stats.put("interactWithBeacon", provider.apply(Stats.INTERACT_WITH_BEACON));  // 与信标交互次数
            stats.put("inspectDropper", provider.apply(Stats.INSPECT_DROPPER));          // 查看投掷器次数
            stats.put("inspectHopper", provider.apply(Stats.INSPECT_HOPPER));            // 查看漏斗次数
            stats.put("inspectDispenser", provider.apply(Stats.INSPECT_DISPENSER));      // 查看发射器次数
            stats.put("playNoteblock", provider.apply(Stats.PLAY_NOTEBLOCK));            // 播放音符盒次数
            stats.put("tuneNoteblock", provider.apply(Stats.TUNE_NOTEBLOCK));            // 调音音符盒次数
            stats.put("potFlower", provider.apply(Stats.POT_FLOWER));                    // 花盆种花次数
            stats.put("triggerTrappedChest", provider.apply(Stats.TRIGGER_TRAPPED_CHEST)); // 触发陷阱箱次数
            stats.put("openEnderchest", provider.apply(Stats.OPEN_ENDERCHEST));          // 打开末影箱次数
            stats.put("enchantItem", provider.apply(Stats.ENCHANT_ITEM));                // 附魔物品次数
            stats.put("playRecord", provider.apply(Stats.PLAY_RECORD));                  // 播放唱片次数
            stats.put("interactWithFurnace", provider.apply(Stats.INTERACT_WITH_FURNACE)); // 与熔炉交互次数
            stats.put("interactWithCraftingTable", provider.apply(Stats.INTERACT_WITH_CRAFTING_TABLE)); // 与工作台交互次数
            stats.put("openChest", provider.apply(Stats.OPEN_CHEST));                    // 打开箱子次数
            stats.put("sleepInBed", provider.apply(Stats.SLEEP_IN_BED));                  // 在床上睡觉次数
            stats.put("openShulkerBox", provider.apply(Stats.OPEN_SHULKER_BOX));          // 打开潜影盒次数
            stats.put("openBarrel", provider.apply(Stats.OPEN_BARREL));                  // 打开木桶次数
            stats.put("interactWithBlastFurnace", provider.apply(Stats.INTERACT_WITH_BLAST_FURNACE)); // 与高炉交互次数
            stats.put("interactWithSmoker", provider.apply(Stats.INTERACT_WITH_SMOKER));  // 与烟熏炉交互次数
            stats.put("interactWithLectern", provider.apply(Stats.INTERACT_WITH_LECTERN)); // 与讲台交互次数
            stats.put("interactWithCampfire", provider.apply(Stats.INTERACT_WITH_CAMPFIRE)); // 与营火交互次数
            stats.put("interactWithCartographyTable", provider.apply(Stats.INTERACT_WITH_CARTOGRAPHY_TABLE)); // 与制图台交互次数
            stats.put("interactWithLoom", provider.apply(Stats.INTERACT_WITH_LOOM));      // 与织布机交互次数
            stats.put("interactWithStonecutter", provider.apply(Stats.INTERACT_WITH_STONECUTTER)); // 与切石机交互次数
            stats.put("bellRing", provider.apply(Stats.BELL_RING));                      // 敲钟次数
            stats.put("raidTrigger", provider.apply(Stats.RAID_TRIGGER));                // 触发袭击次数
            stats.put("raidWin", provider.apply(Stats.RAID_WIN));                        // 袭击胜利次数
            stats.put("interactWithAnvil", provider.apply(Stats.INTERACT_WITH_ANVIL));    // 与铁砧交互次数
            stats.put("interactWithGrindstone", provider.apply(Stats.INTERACT_WITH_GRINDSTONE)); // 与砂轮交互次数
            stats.put("targetHit", provider.apply(Stats.TARGET_HIT));                    // 击中标靶次数
            stats.put("interactWithSmithingTable", provider.apply(Stats.INTERACT_WITH_SMITHING_TABLE)); // 与锻造台交互次数

            // ---- 额外处理：格式化时间 ----
            int playTimeTicks = provider.apply(Stats.PLAY_TIME);
            stats.put("playTimeFormatted", formatPlayTime(playTimeTicks));

        } catch (Exception e) {
            LOGGER.error("Failed to get stats for player {}", playerName, e);
        }

        return stats;
    }

    /**
     * 将 tick 数格式化为可读的时间字符串
     */
    private static String formatPlayTime(int ticks) {
        long seconds = ticks / 20;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        minutes %= 60;
        hours %= 24;
        if (days > 0) {
            return String.format("%d天%d小时%d分钟", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes);
        } else {
            return String.format("%d分钟", minutes);
        }
    }
}