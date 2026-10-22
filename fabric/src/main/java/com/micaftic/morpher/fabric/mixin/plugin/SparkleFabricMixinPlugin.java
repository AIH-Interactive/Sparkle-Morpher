package com.micaftic.morpher.fabric.mixin.plugin;

import com.micaftic.morpher.core.compat.punchy.PunchyBridgeVersions;
import com.micaftic.morpher.core.compat.punchy.PunchyModMatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Punchy! arm bridge only when a Punchy! build with an inspected arm render path is
 * installed. Punchy! is optional, so loading the mixin unconditionally would crash startup on
 * every setup without it, and applying it to an uninspected build could fail injection at class
 * load. Either way the safest outcome is to leave Punchy!'s own arm untouched.
 */
public final class SparkleFabricMixinPlugin implements IMixinConfigPlugin {

    private static final String PUNCHY_ARM_MIXIN =
            "com.micaftic.morpher.fabric.mixin.client.compat.punchy.PunchyArmRendererMixin";

    private static Boolean punchyArmSupported;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!PUNCHY_ARM_MIXIN.equals(mixinClassName)) {
            return true;
        }
        boolean apply = punchyArmSupported();
        System.out.println("[Sparkle Morpher] Punchy arm bridge mixin "
                + (apply ? "enabled" : "skipped (Punchy absent or unsupported version)"));
        return apply;
    }

    private static synchronized boolean punchyArmSupported() {
        if (punchyArmSupported != null) {
            return punchyArmSupported;
        }
        boolean supported = false;
        try {
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                ModMetadata metadata = container.getMetadata();
                if (!PunchyModMatcher.matches(metadata.getId(), metadata.getName())) {
                    continue;
                }
                supported = PunchyBridgeVersions.isSupported(metadata.getVersion().getFriendlyString());
                break;
            }
        } catch (Throwable t) {
            System.out.println("[Sparkle Morpher] Punchy probe failed during mixin setup; arm bridge disabled: " + t);
        }
        punchyArmSupported = supported;
        return supported;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
