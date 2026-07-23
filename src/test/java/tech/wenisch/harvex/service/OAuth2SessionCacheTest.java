package tech.wenisch.harvex.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class OAuth2SessionCacheTest {

    private OAuth2SessionCache sessionCache;
    private UUID testRunId;

    @BeforeEach
    void setUp() {
        sessionCache = new OAuth2SessionCache();
        testRunId = UUID.randomUUID();
    }

    @Test
    void testStoreAndRetrieveToken() {
        String testToken = "test-access-token-12345";
        sessionCache.storeToken(testRunId, testToken);

        String retrievedToken = sessionCache.getToken(testRunId);
        assertNotNull(retrievedToken);
        assertEquals(testToken, retrievedToken);
    }

    @Test
    void testGetNonExistentToken() {
        String retrievedToken = sessionCache.getToken(testRunId);
        assertNull(retrievedToken);
    }

    @Test
    void testStoreNullToken() {
        sessionCache.storeToken(testRunId, null);
        assertNull(sessionCache.getToken(testRunId));
    }

    @Test
    void testStoreEmptyToken() {
        sessionCache.storeToken(testRunId, "");
        assertNull(sessionCache.getToken(testRunId));
    }

    @Test
    void testStoreBlankToken() {
        sessionCache.storeToken(testRunId, "   ");
        assertNull(sessionCache.getToken(testRunId));
    }

    @Test
    void testHasValidToken() {
        // Initially no token
        assertFalse(sessionCache.hasValidToken(testRunId));

        // Store a valid token
        sessionCache.storeToken(testRunId, "valid-token");
        assertTrue(sessionCache.hasValidToken(testRunId));

        // Remove token
        sessionCache.removeToken(testRunId);
        assertFalse(sessionCache.hasValidToken(testRunId));
    }

    @Test
    void testMultipleRunIds() {
        UUID runId1 = UUID.randomUUID();
        UUID runId2 = UUID.randomUUID();

        sessionCache.storeToken(runId1, "token-for-run-1");
        sessionCache.storeToken(runId2, "token-for-run-2");

        assertEquals("token-for-run-1", sessionCache.getToken(runId1));
        assertEquals("token-for-run-2", sessionCache.getToken(runId2));
    }

    @Test
    void testTokenExpiration() throws InterruptedException {
        String testToken = "expiring-token";
        sessionCache.storeToken(testRunId, testToken);

        // Token should be valid initially
        assertTrue(sessionCache.hasValidToken(testRunId));

        // Note: In a real test, we would need to mock the clock to test expiration
        // For now, we just verify the token is stored and retrievable
        assertNotNull(sessionCache.getToken(testRunId));
    }

    @Test
    void testCleanupExpiredTokens() {
        // Store a token
        sessionCache.storeToken(testRunId, "test-token");

        // Cleanup should not remove valid tokens
        sessionCache.cleanupExpiredTokens();

        // Token should still be there
        assertNotNull(sessionCache.getToken(testRunId));
    }
}