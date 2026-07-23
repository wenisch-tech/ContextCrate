package tech.wenisch.harvex.service;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory cache for OAuth2 tokens to replace file-based storage.
 * This provides better performance and reliability for session management.
 */
@Service
public class OAuth2SessionCache {
    private static final Logger log = LoggerFactory.getLogger(OAuth2SessionCache.class);

    // Cache structure: runId -> (token, expirationTime)
    private final Map<UUID, TokenEntry> tokenCache = new ConcurrentHashMap<>();

    // Default token expiration: 1 hour (adjustable based on OAuth2 token lifetime)
    private final Duration defaultTokenExpiration = Duration.ofHours(1);

    /**
     * Stores an OAuth2 token for a specific run
     *
     * @param runId The run ID to associate with the token
     * @param accessToken The OAuth2 access token
     */
    public void storeToken(UUID runId, String accessToken) {
        if (runId == null || accessToken == null || accessToken.isBlank()) {
            log.warn("Attempted to store null/empty token for run {}", runId);
            return;
        }

        Instant expirationTime = Instant.now().plus(defaultTokenExpiration);
        tokenCache.put(runId, new TokenEntry(accessToken, expirationTime));

        log.debug("Stored OAuth2 token for run {} (expires at {})", runId, expirationTime);
    }

    /**
     * Retrieves an OAuth2 token for a specific run
     *
     * @param runId The run ID to retrieve the token for
     * @return The OAuth2 access token, or null if not found or expired
     */
    public String getToken(UUID runId) {
        if (runId == null) {
            log.warn("Attempted to retrieve token for null run ID");
            return null;
        }

        TokenEntry entry = tokenCache.get(runId);
        if (entry == null) {
            log.debug("No OAuth2 token found in cache for run {}", runId);
            return null;
        }

        // Check if token is expired
        if (Instant.now().isAfter(entry.expirationTime())) {
            log.debug("OAuth2 token for run {} has expired", runId);
            tokenCache.remove(runId); // Clean up expired token
            return null;
        }

        log.debug("Found valid OAuth2 token for run {}", runId);
        return entry.token();
    }

    /**
     * Removes a token from the cache
     *
     * @param runId The run ID to remove the token for
     */
    public void removeToken(UUID runId) {
        if (runId == null) {
            return;
        }

        tokenCache.remove(runId);
        log.debug("Removed OAuth2 token for run {}", runId);
    }

    /**
     * Cleans up expired tokens from the cache
     */
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        tokenCache.entrySet().removeIf(entry ->
            now.isAfter(entry.getValue().expirationTime())
        );
        log.debug("Cleaned up expired OAuth2 tokens");
    }

    /**
     * Checks if a valid token exists for a run
     *
     * @param runId The run ID to check
     * @return true if a valid token exists, false otherwise
     */
    public boolean hasValidToken(UUID runId) {
        String token = getToken(runId);
        return token != null && !token.isBlank();
    }

    /**
     * Record representing a cached OAuth2 token with expiration time
     */
    private record TokenEntry(String token, Instant expirationTime) {}
}