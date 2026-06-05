package fr.xephi.authme.velocity.premium;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MojangProfileLookup implements Function<String, Optional<UUID>> {

    private static final Pattern VALID_MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();

    private final Logger logger;
    private final HttpClient httpClient;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    MojangProfileLookup(Logger logger) {
        this(logger, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    MojangProfileLookup(Logger logger, HttpClient httpClient) {
        this.logger = logger;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<UUID> apply(String username) {
        String normalizedName = username.toLowerCase(Locale.ROOT);
        if (!VALID_MINECRAFT_NAME.matcher(normalizedName).matches()) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        CachedResult cachedResult = cache.get(normalizedName);
        if (cachedResult != null && cachedResult.expiresAt() > now) {
            return cachedResult.mojangUuid();
        }

        Optional<UUID> mojangUuid = lookupProfile(normalizedName);
        if (mojangUuid != null) {
            cache.put(normalizedName, new CachedResult(mojangUuid, now + CACHE_MILLIS));
            return mojangUuid;
        }
        return Optional.empty();
    }

    private Optional<UUID> lookupProfile(String username) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
            .timeout(TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                return parseUuid(response.body(), username);
            }
            if (status == 204 || status == 404) {
                return Optional.empty();
            }
            logger.warn("Mojang profile lookup for '{}' returned HTTP {}", username, status);
        } catch (IOException e) {
            logger.warn("Mojang profile lookup for '{}' failed: {}", username, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Mojang profile lookup for '{}' was interrupted", username);
        }
        return null;
    }

    private Optional<UUID> parseUuid(String body, String username) {
        Matcher matcher = UUID_PATTERN.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String raw = matcher.group(1);
        String dashed = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
            + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
        try {
            return Optional.of(UUID.fromString(dashed));
        } catch (IllegalArgumentException e) {
            logger.warn("Mojang returned an unparseable UUID for '{}': {}", username, raw);
            return Optional.empty();
        }
    }

    private record CachedResult(Optional<UUID> mojangUuid, long expiresAt) {
    }
}
