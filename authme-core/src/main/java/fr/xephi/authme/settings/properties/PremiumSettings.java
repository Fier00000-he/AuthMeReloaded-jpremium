package fr.xephi.authme.settings.properties;

import ch.jalu.configme.Comment;
import ch.jalu.configme.SettingsHolder;
import ch.jalu.configme.properties.Property;

import static ch.jalu.configme.properties.PropertyInitializer.newProperty;

public final class PremiumSettings implements SettingsHolder {

    @Comment({
        "Enable premium mode: players with an official Minecraft account",
        "can skip password authentication.",
        "Verification method is chosen automatically:",
        "  - online-mode=true: Bukkit already has the Mojang UUID; no PacketEvents needed.",
        "  - offline-mode + proxy: set Hooks.bungeecord=true; UUID is forwarded by proxy.",
        "  - offline-mode, no proxy: PacketEvents required for cryptographic verification.",
        "    Without PacketEvents, premium auto-login is disabled (fail closed).",
        "Set premiumAutoRegister=false if players must use /premium to opt in."
    })
    public static final Property<Boolean> ENABLE_PREMIUM =
        newProperty("settings.enablePremium", false);

    @Comment({
        "Automatically register verified premium players who do not have an AuthMe account yet.",
        "This gives jPremium-like behavior: legitimate Minecraft accounts can join without",
        "running /register first. Disable this to keep the legacy /premium opt-in flow only."
    })
    public static final Property<Boolean> AUTO_REGISTER_PREMIUM =
        newProperty("settings.premiumAutoRegister", true);

    private PremiumSettings() {
    }

}
