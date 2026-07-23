package tech.wenisch.harvex.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Backward compatibility class for handling old authentication format
 */
public class CrawlConfigurationCompatibility {
    private final CrawlConfiguration config;
    private final Authentication authentication;

    @JsonCreator
    public CrawlConfigurationCompatibility(
            @JsonProperty("scope") CrawlConfiguration.Scope scope,
            @JsonProperty("politeness") CrawlConfiguration.Politeness politeness,
            @JsonProperty("reliability") CrawlConfiguration.Reliability reliability,
            @JsonProperty("output") CrawlConfiguration.Output output,
            @JsonProperty("authentication") Authentication authentication,
            @JsonProperty("loginConfiguration") CrawlConfiguration.LoginConfiguration loginConfiguration) {

        this.authentication = authentication;

        // Convert old authentication format to new loginConfiguration format
        CrawlConfiguration.LoginConfiguration convertedLoginConfig = null;

        if (authentication != null && authentication.isConfigured()) {
            convertedLoginConfig = new CrawlConfiguration.LoginConfiguration(
                authentication.loginPageUrl(),
                authentication.username(),
                authentication.password(),
                authentication.usernameField(),
                authentication.passwordField(),
                authentication.submitSelector(),
                authentication.successDetection(),
                false // directLogin defaults to false for backward compatibility
            );
        } else if (loginConfiguration != null) {
            convertedLoginConfig = loginConfiguration;
        }

        this.config = new CrawlConfiguration(scope, politeness, reliability, output, convertedLoginConfig);
    }

    public CrawlConfiguration getConfig() {
        return config;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Authentication {
        private final String loginPageUrl;
        private final String username;
        private final String password;
        private final String usernameField;
        private final String passwordField;
        private final String submitSelector;
        private final CrawlConfiguration.SuccessDetection successDetection;

        @JsonCreator
        public Authentication(
                @JsonProperty("loginPageUrl") String loginPageUrl,
                @JsonProperty("username") String username,
                @JsonProperty("password") String password,
                @JsonProperty("usernameField") String usernameField,
                @JsonProperty("passwordField") String passwordField,
                @JsonProperty("submitSelector") String submitSelector,
                @JsonProperty("successDetection") CrawlConfiguration.SuccessDetection successDetection) {
            this.loginPageUrl = loginPageUrl;
            this.username = username;
            this.password = password;
            this.usernameField = usernameField;
            this.passwordField = passwordField;
            this.submitSelector = submitSelector;
            this.successDetection = successDetection;
        }

        public boolean isConfigured() {
            return loginPageUrl != null && !loginPageUrl.isBlank() &&
                   username != null && !username.isBlank() &&
                   password != null && !password.isBlank();
        }

        public String loginPageUrl() { return loginPageUrl; }
        public String username() { return username; }
        public String password() { return password; }
        public String usernameField() { return usernameField; }
        public String passwordField() { return passwordField; }
        public String submitSelector() { return submitSelector; }
        public CrawlConfiguration.SuccessDetection successDetection() { return successDetection; }
    }
}
