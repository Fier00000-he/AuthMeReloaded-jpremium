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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class MojangProfileLookup implements Predicate<String> {

    private static final Pattern VALID_MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
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
    public boolean test(String username) {
        String normalizedName = username.toLowerCase(Locale.ROOT);
        if (!VALID_MINECRAFT_NAME.matcher(normalizedName).matches()) {
            return false;
        }

        long now = System.currentTimeMillis();
        CachedResult cachedResult = cache.get(normalizedName);
        if (cachedResult != null && cachedResult.expiresAt() > now) {
            return cachedResult.exists();
        }

        Boolean exists = lookupProfile(normalizedName);
        if (exists != null) {
            cache.put(normalizedName, new CachedResult(exists, now + CACHE_MILLIS));
            return exists;
        }
        return false;
    }

    private Boolean lookupProfile(String username) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
            .timeout(TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 200) {
                return true;
            }
            if (status == 204 || status == 404) {
                return false;
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

    private record CachedResult(boolean exists, long expiresAt) {
    }
}
