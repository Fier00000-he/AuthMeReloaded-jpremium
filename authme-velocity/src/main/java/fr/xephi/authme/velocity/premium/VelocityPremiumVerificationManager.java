package fr.xephi.authme.velocity.premium;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.UuidUtils;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public final class VelocityPremiumVerificationManager {

    private final Logger logger;
    private final Predicate<String> requiresVerification;
    private final Predicate<String> isPendingVerification;
    private final BooleanSupplier keepOfflineUuidCompatibility;
    private final BooleanSupplier verifyUnknownPremiumPlayers;
    private final Function<String, Optional<UUID>> mojangProfileLookup;
    private final Set<String> onlineModeNames = ConcurrentHashMap.newKeySet();
    private final ProxyPremiumLoginVerifier loginVerifier;
    private boolean registered;

    public VelocityPremiumVerificationManager(Logger logger,
                                              Predicate<String> requiresVerification,
                                              Predicate<String> isPendingVerification,
                                              BooleanSupplier keepOfflineUuidCompatibility,
                                              BooleanSupplier verifyUnknownPremiumPlayers) {
        this(logger, requiresVerification, isPendingVerification, keepOfflineUuidCompatibility,
            verifyUnknownPremiumPlayers, new MojangProfileLookup(logger));
    }

    VelocityPremiumVerificationManager(Logger logger,
                                       Predicate<String> requiresVerification,
                                       Predicate<String> isPendingVerification,
                                       BooleanSupplier keepOfflineUuidCompatibility,
                                       BooleanSupplier verifyUnknownPremiumPlayers,
                                       Function<String, Optional<UUID>> mojangProfileLookup) {
        this.logger = logger;
        this.requiresVerification = requiresVerification;
        this.isPendingVerification = isPendingVerification;
        this.keepOfflineUuidCompatibility = keepOfflineUuidCompatibility;
        this.verifyUnknownPremiumPlayers = verifyUnknownPremiumPlayers;
        this.mojangProfileLookup = mojangProfileLookup;
        this.loginVerifier = new ProxyPremiumLoginVerifier("authme-velocity-premium",
            message -> this.logger.warn(message));
    }

    public void register() {
        if (registered) {
            return;
        }
        registered = true;
        logger.info("Registered native Velocity premium verification");
    }

    public void onPreLogin(PreLoginEvent event) {
        String normalizedName = normalize(event.getUsername());
        if (shouldForceOnlineMode(normalizedName, event.getUniqueId())) {
            onlineModeNames.add(normalizedName);
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
        } else {
            onlineModeNames.remove(normalizedName);
        }
    }

    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String normalizedName = normalize(event.getUsername());
        if ((!onlineModeNames.contains(normalizedName) && !requiresVerification.test(normalizedName))
                || !event.isOnlineMode()) {
            return;
        }

        UUID verifiedPremiumUuid = event.getOriginalProfile().getId();
        loginVerifier.storeVerified(normalizedName, verifiedPremiumUuid);
        if (keepOfflineUuidCompatibility.getAsBoolean()) {
            event.setGameProfile(event.getGameProfile().withId(UuidUtils.generateOfflinePlayerUuid(event.getUsername())));
        }

        if (isPendingVerification.test(normalizedName)) {
            logger.info("Premium enrollment for '{}' was verified on the Velocity proxy", normalizedName);
        } else {
            logger.debug("Verified premium login for '{}' on the Velocity proxy", normalizedName);
        }
    }

    private boolean shouldForceOnlineMode(String normalizedName, UUID claimedUuid) {
        if (requiresVerification.test(normalizedName)) {
            return true;
        }
        if (!verifyUnknownPremiumPlayers.getAsBoolean() || claimedUuid == null) {
            return false;
        }

        Optional<UUID> mojangUuid = mojangProfileLookup.apply(normalizedName);
        if (mojangUuid.isEmpty()) {
            return false;
        }

        boolean matchesClaimedUuid = mojangUuid.get().equals(claimedUuid);
        if (!matchesClaimedUuid) {
            logger.debug("Allowing '{}' to continue in offline-mode: client UUID {} does not match Mojang UUID {}",
                normalizedName, claimedUuid, mojangUuid.get());
        }
        return matchesClaimedUuid;
    }

    public UUID getVerifiedPremiumUuid(String normalizedName) {
        return loginVerifier.getVerifiedUuid(normalizedName);
    }

    public void clearVerifiedPremium(String normalizedName) {
        loginVerifier.clearVerified(normalizedName);
        onlineModeNames.remove(normalizedName);
    }

    public void shutdown() {
        if (!registered) {
            return;
        }
        registered = false;
        onlineModeNames.clear();
        loginVerifier.shutdown();
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
