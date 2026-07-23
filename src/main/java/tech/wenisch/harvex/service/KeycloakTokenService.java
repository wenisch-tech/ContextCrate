package tech.wenisch.harvex.service;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class KeycloakTokenService {
    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenService.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KeycloakTokenService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(createTrustAllSslContext())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates an SSL context that trusts all certificates (for self-signed certs)
     */
    private static javax.net.ssl.SSLContext createTrustAllSslContext() {
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            }, new java.security.SecureRandom());
            return sslContext;
        } catch (java.security.NoSuchAlgorithmException | java.security.KeyManagementException e) {
            log.warn("Failed to create trust-all SSL context, falling back to default: {}", e.getMessage());
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException ex) {
                log.error("Failed to get default SSL context: {}", ex.getMessage());
                return null;
            }
        }
    }

    /**
     * Retrieves an access token using OAuth2 client credentials flow
     *
     * @param authServerUrl Keycloak auth server URL (e.g., "https://keycloak.example.com")
     * @param realm Keycloak realm name
     * @param clientId OAuth2 client ID
     * @param clientSecret OAuth2 client secret
     * @return Access token
     * @throws Exception if token retrieval fails
     */
    public String getAccessToken(String authServerUrl, String realm, String clientId, String clientSecret) throws Exception {
        // Validate inputs
        if (authServerUrl == null || authServerUrl.isBlank()) {
            throw new IllegalArgumentException("Auth server URL cannot be blank");
        }
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("Realm cannot be blank");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID cannot be blank");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Client secret cannot be blank");
        }

        // Construct token endpoint URL
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token",
            authServerUrl.endsWith("/") ? authServerUrl.substring(0, authServerUrl.length() - 1) : authServerUrl,
            realm);

        log.debug("Requesting OAuth2 token from: {}", tokenUrl);

        // Prepare form data for client credentials grant
        String formData = String.format(
            "client_id=%s&client_secret=%s&grant_type=client_credentials",
            java.net.URLEncoder.encode(clientId, "UTF-8"),
            java.net.URLEncoder.encode(clientSecret, "UTF-8")
        );

        // Build and send request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        // Handle response
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                // Parse JSON response
                Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                String accessToken = (String) responseMap.get("access_token");

                if (accessToken == null || accessToken.isBlank()) {
                    throw new Exception("No access_token found in OAuth2 response");
                }

                log.debug("Successfully obtained OAuth2 access token");
                return accessToken;
            } catch (Exception e) {
                log.error("Failed to parse OAuth2 token response: {}", e.getMessage());
                throw new Exception("Failed to parse OAuth2 token response: " + e.getMessage());
            }
        } else {
            log.error("OAuth2 token request failed with status {}: {}", response.statusCode(), response.body());
            throw new Exception(String.format("OAuth2 token request failed with status %d: %s",
                response.statusCode(), response.body()));
        }
    }

    /**
     * Retrieves an access token using OAuth2 password grant (for testing/development)
     *
     * @param authServerUrl Keycloak auth server URL
     * @param realm Keycloak realm name
     * @param clientId OAuth2 client ID
     * @param clientSecret OAuth2 client secret
     * @param username Username
     * @param password Password
     * @return Access token
     * @throws Exception if token retrieval fails
     */
    public String getAccessTokenWithPassword(String authServerUrl, String realm, String clientId,
                                          String clientSecret, String username, String password) throws Exception {
        // Validate inputs
        if (authServerUrl == null || authServerUrl.isBlank()) {
            throw new IllegalArgumentException("Auth server URL cannot be blank");
        }
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("Realm cannot be blank");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID cannot be blank");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Client secret cannot be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }

        // Construct token endpoint URL
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token",
            authServerUrl.endsWith("/") ? authServerUrl.substring(0, authServerUrl.length() - 1) : authServerUrl,
            realm);

        log.debug("Requesting OAuth2 token with password grant from: {}", tokenUrl);

        // Prepare form data for password grant
        String formData = String.format(
            "client_id=%s&client_secret=%s&grant_type=password&username=%s&password=%s",
            java.net.URLEncoder.encode(clientId, "UTF-8"),
            java.net.URLEncoder.encode(clientSecret, "UTF-8"),
            java.net.URLEncoder.encode(username, "UTF-8"),
            java.net.URLEncoder.encode(password, "UTF-8")
        );

        // Build and send request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        // Handle response
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                // Parse JSON response
                Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
                String accessToken = (String) responseMap.get("access_token");

                if (accessToken == null || accessToken.isBlank()) {
                    throw new Exception("No access_token found in OAuth2 response");
                }

                log.debug("Successfully obtained OAuth2 access token with password grant");
                return accessToken;
            } catch (Exception e) {
                log.error("Failed to parse OAuth2 token response: {}", e.getMessage());
                throw new Exception("Failed to parse OAuth2 token response: " + e.getMessage());
            }
        } else {
            log.error("OAuth2 token request failed with status {}: {}", response.statusCode(), response.body());
            throw new Exception(String.format("OAuth2 token request failed with status %d: %s",
                response.statusCode(), response.body()));
        }
    }
}