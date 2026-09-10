package cn.blockforge.is_boss;

import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(GeneratedMod.MOD_ID)
public final class GeneratedMod {
    public static final String MOD_ID = "is_this_the_boss";
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MOD_ID);
    public static final RegistryObject<MobEffect> BOSS_HATRED =
            MOB_EFFECTS.register("boss_hatred", BossEffect::new);

    public GeneratedMod() {
        repairLegacyConfig();
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(BossEvents::onConfigReload);
        MOB_EFFECTS.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BossConfig.SPEC,
                "is_this_the_boss-common.toml");
    }

    private static void repairLegacyConfig() {
        Path configPath = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("is_this_the_boss-common.toml");
        if (!Files.isRegularFile(configPath)) {
            return;
        }
        try {
            String original = Files.readString(configPath, StandardCharsets.UTF_8);
            String repaired = quoteLegacyPhaseTransitions(original);
            if (!original.equals(repaired)) {
                Files.writeString(configPath, repaired, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Forge will report an unreadable config with its normal configuration error.
        }
    }

    private static String quoteLegacyPhaseTransitions(String content) {
        String[] lines = content.split("\\R", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("phase_transitions") || !trimmed.contains("=")) {
                continue;
            }
            int open = lines[i].indexOf('[', lines[i].indexOf('='));
            if (open < 0) {
                continue;
            }
            StringBuilder block = new StringBuilder(lines[i].substring(open));
            int endLine = i;
            int close = block.indexOf("]");
            while (close < 0 && endLine + 1 < lines.length) {
                endLine++;
                block.append('\n').append(lines[endLine]);
                close = block.indexOf("]");
            }
            if (close < 0) {
                continue;
            }
            String inside = block.substring(1, close).trim();
            if (!inside.contains("->") || inside.contains("\"")) {
                continue;
            }
            StringBuilder list = new StringBuilder("[");
            for (String value : inside.split("\\s*,\\s*")) {
                String entry = value.trim();
                if (!entry.isEmpty()) {
                    if (list.length() > 1) {
                        list.append(", ");
                    }
                    list.append('"').append(escapeToml(entry)).append('"');
                }
            }
            list.append(']');
            String before = lines[i].substring(0, open);
            String after = block.substring(close + 1);
            lines[i] = before + list + after;
            for (int line = i + 1; line <= endLine; line++) {
                lines[line] = "";
            }
            changed = true;
        }
        if (!changed) {
            return content;
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
