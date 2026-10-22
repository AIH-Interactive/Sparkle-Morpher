package com.micaftic.morpher.core.compat.punchy.fabric;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.core.compat.punchy.PunchyModMatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class PunchyCompatImpl {

    private static volatile boolean resolved;
    private static volatile String modId;
    private static volatile String version;

    private PunchyCompatImpl() {
    }

    public static boolean isLoaded() {
        resolve();
        return modId != null;
    }

    public static String modId() {
        resolve();
        return modId;
    }

    public static String version() {
        resolve();
        return version;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (PunchyCompatImpl.class) {
            if (resolved) {
                return;
            }
            String foundId = null;
            String foundVersion = null;
            try {
                for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                    String id = container.getMetadata().getId();
                    String name = container.getMetadata().getName();
                    if (PunchyModMatcher.matches(id, name)) {
                        foundId = id;
                        foundVersion = container.getMetadata().getVersion().getFriendlyString();
                        break;
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // Never let detection break rendering; log instead of silently reporting absent.
                YesSteveModel.LOGGER.warn("Punchy! detection failed; assuming not installed", e);
            }
            modId = foundId;
            version = foundVersion;
            resolved = true;
        }
    }
}
