package tech.wenisch.harvex.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakTokenServiceTest {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenServiceTest.class);

    @Test
    @Disabled("This is a manual test - enable and configure with your Keycloak settings")
    void testGetAccessTokenManually() {
        // Create the service
        KeycloakTokenService service = new KeycloakTokenService();

        // Configure with your Keycloak settings
        String authServerUrl = "https://your-keycloak-server.com";
        String realm = "your-realm";
        String clientId = "harvex";
        String clientSecret = "topsecret";

        try {
            // Test the token retrieval
            String accessToken = service.getAccessToken(authServerUrl, realm, clientId, clientSecret);

            assertNotNull(accessToken, "Access token should not be null");
            assertFalse(accessToken.isBlank(), "Access token should not be blank");

            log.info("Successfully obtained OAuth2 access token!");
            log.info("Token length: {} characters", accessToken.length());
            log.info("Token starts with: {}", accessToken.substring(0, Math.min(20, accessToken.length())));

        } catch (Exception e) {
            log.error("Failed to obtain OAuth2 token: {}", e.getMessage());
            fail("Failed to obtain OAuth2 token: " + e.getMessage());
        }
    }

    @Test
    @Disabled("This is a manual test - enable and configure with your Keycloak settings")
    void testOAuth2TokenStorage() {
        // Test the token storage mechanism
        java.io.File tokenFile = new java.io.File("oauth2_token_test.txt");

        try {
            // Write a test token
            String testToken = "test-oauth2-token-12345";
            java.nio.file.Files.writeString(tokenFile.toPath(), testToken);

            // Verify it was written
            String readToken = java.nio.file.Files.readString(tokenFile.toPath());
            assertEquals(testToken, readToken, "Token should be written and read correctly");

            // Clean up
            tokenFile.delete();

            log.info("Token storage mechanism works correctly");

        } catch (Exception e) {
            log.error("Token storage test failed: {}", e.getMessage());
            fail("Token storage test failed: " + e.getMessage());
        }
    }
}