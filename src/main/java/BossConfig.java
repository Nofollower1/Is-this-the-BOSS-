package cn.blockforge.is_boss;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class BossConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue MINIMUM_MAX_HEALTH;
    public static final ForgeConfigSpec.DoubleValue SINGLE_HIT_PERCENT;
    public static final ForgeConfigSpec.DoubleValue DPS_PERCENT;
    public static final ForgeConfigSpec.DoubleValue AGGRO_EXTRA_RANGE;
    public static final ForgeConfigSpec.BooleanValue GENERAL_BOSS_REPLACE_ON_REFRESH;
    public static final ForgeConfigSpec.BooleanValue REFRESH_ON_PLAYER_DEATH;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPECIAL_ITEM_LIMITS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> UNIQUE_BOSS_LIMITS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WHITELIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PHASE_TRANSITIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DAMAGE_LIMIT_EXEMPT_TYPES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Is this the BOSS? Server General Configuration").push("boss_rules");

        MINIMUM_MAX_HEALTH = builder
                .comment("Entities with maximum health this value will become a BOSS. Default 100 represents 50 hearts.")
                .defineInRange("minimum_max_health", 100.0D, 0.0D, Double.MAX_VALUE);
        SINGLE_HIT_PERCENT = builder
                .comment("BOSS hit damage cap, as a percentage of the BOSS's maximum health. Default 10.")
                .defineInRange("single_hit_percent", 10.0D, 0.0D, 100.0D);
        DPS_PERCENT = builder
                .comment("BOSS damage per second quota, as a percentage of the BOSS's maximum health. Default 6. The quota recovers by DPS/20 every tick.")
                .defineInRange("dps_percent", 6.0D, 0.0D, 100.0D);
        AGGRO_EXTRA_RANGE = builder
                .comment("Extra range in blocks outside the BOSS's FOLLOW_RANGE (aggro range) to apply the leader aggro effect, preventing health from due to weapons. Default 15.")
                .defineInRange("aggro_extra_range", 15.0D, 0.0D, 1024.0D);
        GENERAL_BOSS_REPLACE_ON_REFRESH = builder
                .comment("Whether an ordinary BOSS is replaced on refresh. false heals it to full health; true removes and respawns it. Phase-transition BOSSes are always removed and respawned.")
                .define("general_boss_replace_on_refresh", false);
        REFRESH_ON_PLAYER_DEATH = builder
                .comment("Whether to enable the BOSS refresh/replaced upon the death of any player within its FOLLOW_RANGE (aggro range). This feature is mainly prepared for multiplayer games. false by default")
                .define("refresh_on_player_death", false);
        SPECIAL_ITEM_LIMITS = builder
                .comment("Special item damage limit. Each entry uses item_id=items_single_hit_percent; the DPS percentage is scaled by the ratio of items_single_hit_percent/single_hit_percent based on the boss's dps_percent (single_hit_percent is the boss's single_hit_percent), with a maximum cap of 100. Invalid IDs and values will be ignored.")
                .defineListAllowEmpty("special_item_limits", List.of(
                        "twilightforest:glass_sword=40",
                        "minecraft:netherite_sword=8"
                ), value -> value instanceof String);
        UNIQUE_BOSS_LIMITS = builder
                .comment("Unique damage and DPS limit values are written here for certain BOSS entities, rather than directly using the x% maximum health value specified in the configuration. Note that the for single_hit_limit and dps_limit are in points, not as a percentage of the BOSS's maximum health. Format is: entity_id=single_hit_limit,dps_limit.")
                .defineListAllowEmpty("unique_boss_limits", List.of(
                        "minecraft:ender_dragon=35,20",
                        "minecraft:wither=50,25"
                ), value -> value instanceof String);
        BLACKLIST = builder
                .comment("Blacklist: Even if health requirements are met, these will not become a BOSS. Invalid IDs will be ignored.")
                .defineListAllowEmpty("blacklist", List.of(
                        "minecraft:player",
                        "minecraft:iron_golem",
                        "minecraft:ravager",
                        "minecraft:warden",
                        "minecraft:piglin_brute",
                        "powerful_dummy:test_dummy",
                        "dummmmmmy:target_dummy"
                ), value -> value instanceof String);
        WHITELIST = builder
                .comment("Whitelist: Forced to become a BOSS, even if health requirements are met. Invalid IDs will be ignored.")
                .defineListAllowEmpty("whitelist", List.of(
                        "salmonsgenesisreincarnation:salmon_boss"
                ), value -> value instanceof String);
        PHASE_TRANSITIONS = builder
                .comment("For modpack authors only: bosses in the mod that have phase transitions. If there are multiple phases, list all phases. The mod will automatically recognize them. This mod will only help you identify the phase boss and regenerate it, and will not execute the boss phase transition function.Write each line as Current Phase Entity ID -> Next Phase Entity ID; invalid IDs will be automatically skipped. Example: wrd:necromancer_stage_1 -> wrd:necromancer_stage_2; wrd:ice_mage_stage_1 -> wrd:ice_mage_stage_2")
                .defineListAllowEmpty("phase_transitions", List.of("phase1 -> phase2；phase2 -> phase3"), value -> value instanceof String);
        DAMAGE_LIMIT_EXEMPT_TYPES = builder
                .comment("Damage limit exemptions: These damage types will not be subject to the single hit damage cap and DPS limit. Default void damage minecraft:out_of_world and minecraft:generic_kill; invalid IDs will be ignored.")
                .defineListAllowEmpty("damage_limit_exempt_types", List.of(
                        "minecraft:out_of_world",
                        "minecraft:generic_kill"
                ), value -> value instanceof String);

        builder.pop();
        SPEC = builder.build();
    }

    private BossConfig() {
    }
}
