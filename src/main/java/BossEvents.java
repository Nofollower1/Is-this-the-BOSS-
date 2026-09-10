package cn.blockforge.is_boss;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = GeneratedMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossEvents {
    private static final String BUDGET_TAG = "boss_damage_budget";
    private static final String BUDGET_TICK_TAG = "boss_damage_budget_tick";
    private static final String BOSS_SEEN_TAG = "boss_seen_player";
    private static final int REFRESH_SCAN_INTERVAL = 10;
    private static final Map<UUID, PendingRespawn> PENDING_RESPAWNS = new HashMap<>();
    private static final Set<LivingEntity> TRACKED_BOSSES = new HashSet<>();
    private static final Map<UUID, Set<UUID>> BOSS_AGGRO_PLAYERS = new HashMap<>();
    private static final Map<ResourceLocation, ResourceLocation> PHASE_NEXT = new LinkedHashMap<>();
    private static final Map<ResourceLocation, ResourceLocation> PHASE_PREVIOUS = new HashMap<>();
    private static final Map<ResourceLocation, ResourceLocation> PHASE_FIRST = new HashMap<>();
    private static final Set<ResourceLocation> PHASE_ENTITY_IDS = new HashSet<>();
    private static Set<ResourceLocation> WHITELIST_IDS = Set.of();
    private static Set<ResourceLocation> BLACKLIST_IDS = Set.of();
    private static Set<ResourceLocation> DAMAGE_LIMIT_EXEMPT_IDS = Set.of();
    private static Map<ResourceLocation, Double> SPECIAL_ITEM_LIMITS = Map.of();
    private static Map<ResourceLocation, UniqueBossLimits> UNIQUE_BOSS_LIMITS = Map.of();
    private static boolean CONFIG_CACHE_READY;

    private BossEvents() {
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent event) {
        if (event.getConfig().getSpec() == BossConfig.SPEC) {
            CONFIG_CACHE_READY = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity boss = event.getEntity();
        if (boss.level().isClientSide || !isBoss(boss)) {
            return;
        }

        ensureConfigCache();
        if (isDamageLimitBypassed(event.getSource())) {
            return;
        }
        DamageLimits limits = damageLimits(event.getSource(), boss);
        double maximumHealth = boss.getMaxHealth();
        double singleHitLimit = limits.singleHitPercent() * maximumHealth / 100.0D;
        double dpsLimit = Math.min(100.0D, limits.dpsPercent()) * maximumHealth / 100.0D;
        long gameTime = boss.level().getGameTime();
        double budget = boss.getPersistentData().contains(BUDGET_TAG)
                ? boss.getPersistentData().getDouble(BUDGET_TAG)
                : singleHitLimit;
        long lastTick = boss.getPersistentData().getLong(BUDGET_TICK_TAG);
        long elapsedTicks = boss.getPersistentData().contains(BUDGET_TICK_TAG)
                ? Math.max(0L, gameTime - lastTick)
                : 0L;
        budget = Math.min(singleHitLimit, budget + elapsedTicks * dpsLimit / 20.0D);
        boss.getPersistentData().putLong(BUDGET_TICK_TAG, gameTime);

        float requested = event.getAmount();
        float allowed = (float) Math.min(requested, Math.max(0.0D, budget));
        event.setAmount(allowed);
        boss.getPersistentData().putDouble(BUDGET_TAG, Math.max(0.0D, budget - allowed));

    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        ensureConfigCache();
        if (isBoss(living)) {
            TRACKED_BOSSES.add(living);
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player) || player.level().isClientSide || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        ensureConfigCache();
        if (player.tickCount % REFRESH_SCAN_INTERVAL != 0) {
            return;
        }
        for (LivingEntity boss : List.copyOf(TRACKED_BOSSES)) {
            if (boss.level() != level || !boss.isAlive()) {
                continue;
            }
            double range = aggroRange(boss);
            boolean isTarget = boss instanceof Mob mob && mob.getTarget() == player;
            if (isTarget || boss.distanceToSqr(player) <= range * range) {
                markBossAggro(boss, player);
            }
        }
    }

    @SubscribeEvent
    public static void onBossDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        ensureConfigCache();
        TRACKED_BOSSES.remove(dead);
        BOSS_AGGRO_PLAYERS.remove(dead.getUUID());

        if (!BossConfig.REFRESH_ON_PLAYER_DEATH.get()
                || !(dead instanceof Player player) || player.isSpectator()) {
            return;
        }
        UUID deadPlayerId = player.getUUID();
        for (LivingEntity boss : List.copyOf(TRACKED_BOSSES)) {
            if (boss.level() != level || !boss.isAlive()) {
                continue;
            }
            double range = aggroRange(boss);
            boolean wasInAggro = BOSS_AGGRO_PLAYERS.getOrDefault(boss.getUUID(), Set.of()).contains(deadPlayerId);
            boolean isInArea = boss.distanceToSqr(player) <= range * range;
            if (wasInAggro || isInArea) {
                refreshBoss(boss);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ensureConfigCache();
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, PendingRespawn> entry : PENDING_RESPAWNS.entrySet()) {
            PendingRespawn pending = entry.getValue();
            pending.delay--;
            if (pending.delay <= 0 && pending.level.isLoaded(pending.position)) {
                respawnIfNeeded(pending);
                finished.add(entry.getKey());
            }
        }
        finished.forEach(PENDING_RESPAWNS::remove);

        if (event.getServer().getTickCount() % REFRESH_SCAN_INTERVAL != 0) {
            return;
        }
        TRACKED_BOSSES.removeIf(boss -> !boss.isAlive() || boss.isRemoved() || !(boss.level() instanceof ServerLevel));
        for (LivingEntity boss : List.copyOf(TRACKED_BOSSES)) {
            if (!(boss.level() instanceof ServerLevel level) || !boss.isAlive()) {
                continue;
            }
            double range = aggroRange(boss);
            boolean playerNearby = !level.getEntitiesOfClass(Player.class,
                    boss.getBoundingBox().inflate(range), BossEvents::isEffectivePlayer).isEmpty();
            if (boss.getPersistentData().getBoolean(BOSS_SEEN_TAG) && !playerNearby) {
                refreshBoss(boss);
            }
        }
    }

    private static void markBossAggro(LivingEntity boss, Player player) {
        BOSS_AGGRO_PLAYERS.computeIfAbsent(boss.getUUID(), ignored -> new HashSet<>()).add(player.getUUID());
        // The effect is only an internal marker; keep it hidden from every client UI.
        player.addEffect(new MobEffectInstance(GeneratedMod.BOSS_HATRED.get(), 40, 0, false, false, false));
        boss.getPersistentData().putBoolean(BOSS_SEEN_TAG, true);
    }

    private static void clearBossAggro(LivingEntity boss) {
        BOSS_AGGRO_PLAYERS.remove(boss.getUUID());
        boss.getPersistentData().remove(BOSS_SEEN_TAG);
    }

    private static void scheduleRespawn(ServerLevel level, LivingEntity dead, ResourceLocation sourceId) {
        if (PENDING_RESPAWNS.containsKey(dead.getUUID())) {
            return;
        }
        PENDING_RESPAWNS.put(dead.getUUID(), new PendingRespawn(
                level, dead.blockPosition(), dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), dead.getXRot(), sourceId));
    }

    private static void refreshBoss(LivingEntity boss) {
        if (!boss.isAlive() || PENDING_RESPAWNS.containsKey(boss.getUUID())) {
            return;
        }
        if (!isPhaseBoss(boss) && !BossConfig.GENERAL_BOSS_REPLACE_ON_REFRESH.get()) {
            boss.setHealth(boss.getMaxHealth());
            boss.getPersistentData().remove(BUDGET_TAG);
            boss.getPersistentData().remove(BUDGET_TICK_TAG);
            clearBossAggro(boss);
            return;
        }
        ResourceLocation currentId = EntityType.getKey(boss.getType());
        ResourceLocation respawnId = isPhaseBoss(boss) ? findFirstStage(currentId) : currentId;
        scheduleRespawn((ServerLevel) boss.level(), boss, respawnId);
        clearBossAggro(boss);
        // Refresh removes the old entity without firing death logic, phase transitions, or loot drops.
        boss.discard();
    }

    private static void respawnIfNeeded(PendingRespawn pending) {
        EntityType<?> type = findEntityType(pending.sourceId);
        if (type == null) {
            return;
        }
        Entity entity = type.create(pending.level);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        living.moveTo(pending.x, pending.y, pending.z, pending.yaw, pending.pitch);
        pending.level.addFreshEntity(entity);
    }

    private static boolean isEffectivePlayer(Player player) {
        return player.isAlive() && !player.isSpectator();
    }

    private static boolean isDamageLimitBypassed(DamageSource source) {
        ResourceLocation damageTypeId = source.typeHolder().unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        return damageTypeId != null && DAMAGE_LIMIT_EXEMPT_IDS.contains(damageTypeId);
    }

    private static DamageLimits damageLimits(DamageSource source, LivingEntity boss) {
        double maximumHealth = boss.getMaxHealth();
        UniqueBossLimits uniqueLimits = UNIQUE_BOSS_LIMITS.get(EntityType.getKey(boss.getType()));
        double bossSingleHitPercent = uniqueLimits == null
                ? BossConfig.SINGLE_HIT_PERCENT.get()
                : percentOfHealth(uniqueLimits.singleHitLimit(), maximumHealth);
        double bossDpsPercent = uniqueLimits == null
                ? BossConfig.DPS_PERCENT.get()
                : percentOfHealth(uniqueLimits.dpsLimit(), maximumHealth);
        ResourceLocation itemId = itemIdFromSource(source);
        double singleHitPercent = itemId == null
                ? bossSingleHitPercent
                : SPECIAL_ITEM_LIMITS.getOrDefault(itemId, bossSingleHitPercent);
        double dpsPercent = bossDpsPercent * singleHitPercent / Math.max(0.0001D, bossSingleHitPercent);
        return new DamageLimits(singleHitPercent, Math.min(100.0D, dpsPercent));
    }

    private static double percentOfHealth(double points, double maximumHealth) {
        if (maximumHealth <= 0.0D) {
            return 0.0D;
        }
        return Math.min(100.0D, points * 100.0D / maximumHealth);
    }

    private static ResourceLocation itemIdFromSource(DamageSource source) {
        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity living)) {
            return null;
        }
        ResourceLocation mainHand = ForgeRegistries.ITEMS.getKey(living.getMainHandItem().getItem());
        if (mainHand != null && SPECIAL_ITEM_LIMITS.containsKey(mainHand)) {
            return mainHand;
        }
        ResourceLocation offHand = ForgeRegistries.ITEMS.getKey(living.getOffhandItem().getItem());
        return offHand != null && SPECIAL_ITEM_LIMITS.containsKey(offHand) ? offHand : null;
    }

    private static boolean isPhaseBoss(LivingEntity entity) {
        ensureConfigCache();
        return PHASE_ENTITY_IDS.contains(EntityType.getKey(entity.getType()));
    }

    private static double aggroRange(LivingEntity boss) {
        double followRange = boss.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        return Math.max(0.0D, followRange) + BossConfig.AGGRO_EXTRA_RANGE.get();
    }

    public static boolean isBoss(LivingEntity entity) {
        if (entity instanceof Player) {
            return false;
        }
        ensureConfigCache();
        ResourceLocation id = EntityType.getKey(entity.getType());
        if (PHASE_ENTITY_IDS.contains(id)) {
            return true;
        }
        if (WHITELIST_IDS.contains(id)) {
            return true;
        }
        if (BLACKLIST_IDS.contains(id)) {
            return false;
        }
        return entity.getMaxHealth() >= BossConfig.MINIMUM_MAX_HEALTH.get();
    }

    private static ResourceLocation findFirstStage(ResourceLocation id) {
        ResourceLocation current = id;
        Set<ResourceLocation> visited = new HashSet<>();
        while (visited.add(current)) {
            ResourceLocation previous = PHASE_PREVIOUS.get(current);
            if (previous == null) {
                return current;
            }
            current = previous;
        }
        // A cyclic configuration has no unique first stage; keep the current ID stable.
        return id;
    }

    private static void ensureConfigCache() {
        if (CONFIG_CACHE_READY) {
            return;
        }
        rebuildConfigCache();
    }

    private static void rebuildConfigCache() {
        Map<ResourceLocation, ResourceLocation> next = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> previous = new HashMap<>();
        Set<ResourceLocation> phaseIds = new HashSet<>();
        for (Phase phase : readPhases()) {
            // Keep the first configured edge when duplicate entries exist, so the chain is stable.
            next.putIfAbsent(phase.from(), phase.to());
            previous.putIfAbsent(phase.to(), phase.from());
            phaseIds.add(phase.from());
            phaseIds.add(phase.to());
        }

        Map<ResourceLocation, ResourceLocation> first = new HashMap<>();
        for (ResourceLocation id : phaseIds) {
            ResourceLocation current = id;
            Set<ResourceLocation> visited = new HashSet<>();
            while (visited.add(current)) {
                ResourceLocation parent = previous.get(current);
                if (parent == null) {
                    break;
                }
                current = parent;
            }
            first.put(id, current);
        }
        PHASE_NEXT.clear();
        PHASE_NEXT.putAll(next);
        PHASE_PREVIOUS.clear();
        PHASE_PREVIOUS.putAll(previous);
        PHASE_FIRST.clear();
        PHASE_FIRST.putAll(first);
        PHASE_ENTITY_IDS.clear();
        PHASE_ENTITY_IDS.addAll(phaseIds);
        Set<ResourceLocation> whitelistIds = readIds(BossConfig.WHITELIST.get());
        BLACKLIST_IDS = readIds(BossConfig.BLACKLIST.get());
        DAMAGE_LIMIT_EXEMPT_IDS = readIdsWithoutEntityType(BossConfig.DAMAGE_LIMIT_EXEMPT_TYPES.get());
        SPECIAL_ITEM_LIMITS = readSpecialItemLimits(BossConfig.SPECIAL_ITEM_LIMITS.get());
        UNIQUE_BOSS_LIMITS = readUniqueBossLimits(BossConfig.UNIQUE_BOSS_LIMITS.get(), BLACKLIST_IDS);
        // A valid unique entry also acts as a runtime whitelist entry for low-health bosses.
        Set<ResourceLocation> effectiveWhitelist = new HashSet<>(whitelistIds);
        effectiveWhitelist.addAll(UNIQUE_BOSS_LIMITS.keySet());
        WHITELIST_IDS = effectiveWhitelist;
        CONFIG_CACHE_READY = true;
    }

    private static List<Phase> readPhases() {
        List<Phase> phases = new ArrayList<>();
        for (String raw : BossConfig.PHASE_TRANSITIONS.get()) {
            // Accept both ASCII and Chinese semicolons so one config line can list a full chain.
            for (String transition : raw.split("[;；]")) {
                String[] pieces = transition.split("->", 2);
                if (pieces.length != 2) {
                    continue;
                }
                ResourceLocation from = ResourceLocation.tryParse(pieces[0].trim());
                ResourceLocation to = ResourceLocation.tryParse(pieces[1].trim());
                if (from != null && to != null && findEntityType(from) != null && findEntityType(to) != null) {
                    phases.add(new Phase(from, to));
                }
            }
        }
        return phases;
    }

    private static EntityType<?> findEntityType(ResourceLocation id) {
        return ForgeRegistries.ENTITY_TYPES.getValue(id);
    }

    private static Set<ResourceLocation> readIds(List<? extends String> values) {
        Set<ResourceLocation> result = new HashSet<>();
        for (String value : values) {
            ResourceLocation id = ResourceLocation.tryParse(value == null ? "" : value.trim());
            if (id != null && findEntityType(id) != null) {
                result.add(id);
            }
        }
        return result;
    }

    private static Set<ResourceLocation> readIdsWithoutEntityType(List<? extends String> values) {
        Set<ResourceLocation> result = new HashSet<>();
        for (String value : values) {
            ResourceLocation id = ResourceLocation.tryParse(value == null ? "" : value.trim());
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private static Map<ResourceLocation, Double> readSpecialItemLimits(List<? extends String> values) {
        Map<ResourceLocation, Double> result = new HashMap<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String[] pieces = value.split("=", 2);
            if (pieces.length != 2) {
                continue;
            }
            ResourceLocation itemId = ResourceLocation.tryParse(pieces[0].trim());
            if (itemId == null || ForgeRegistries.ITEMS.getValue(itemId) == null) {
                continue;
            }
            try {
                double percent = Double.parseDouble(pieces[1].trim());
                if (Double.isFinite(percent) && percent >= 0.0D && percent <= 100.0D) {
                    result.putIfAbsent(itemId, percent);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries and keep the remaining configuration usable.
            }
        }
        return result;
    }

    private static Map<ResourceLocation, UniqueBossLimits> readUniqueBossLimits(
            List<? extends String> values, Set<ResourceLocation> blacklistIds) {
        Map<ResourceLocation, UniqueBossLimits> result = new HashMap<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String[] pieces = value.split("=", 2);
            if (pieces.length != 2) {
                continue;
            }
            ResourceLocation entityId = ResourceLocation.tryParse(pieces[0].trim());
            EntityType<?> entityType = entityId == null ? null : findEntityType(entityId);
            if (entityId == null || entityType == null || blacklistIds.contains(entityId)) {
                continue;
            }
            String[] limits = pieces[1].split(",", 2);
            if (limits.length != 2) {
                continue;
            }
            try {
                double singleHitLimit = Double.parseDouble(limits[0].trim());
                double dpsLimit = Double.parseDouble(limits[1].trim());
                if (Double.isFinite(singleHitLimit) && Double.isFinite(dpsLimit)
                        && singleHitLimit >= 0.0D && dpsLimit >= 0.0D) {
                    result.putIfAbsent(entityId, new UniqueBossLimits(singleHitLimit, dpsLimit));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries and keep the remaining configuration usable.
            }
        }
        return result;
    }

    private record Phase(ResourceLocation from, ResourceLocation to) {
    }

    private record DamageLimits(double singleHitPercent, double dpsPercent) {
    }

    private record UniqueBossLimits(double singleHitLimit, double dpsLimit) {
    }

    private static final class PendingRespawn {
        private final ServerLevel level;
        private final net.minecraft.core.BlockPos position;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final ResourceLocation sourceId;
        private int delay = 2;

        private PendingRespawn(ServerLevel level, net.minecraft.core.BlockPos position, double x, double y, double z,
                               float yaw, float pitch, ResourceLocation sourceId) {
            this.level = level;
            this.position = position;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.sourceId = sourceId;
        }
    }
}
