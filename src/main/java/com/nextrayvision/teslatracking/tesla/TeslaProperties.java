package com.nextrayvision.teslatracking.tesla;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "tesla")
public record TeslaProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizeUrl,
        String tokenUrl,
        String fleetApiBaseUrl,
        String scopes,
        String dataDir,
        Duration pollInterval,
        Duration parkedPollInterval) {

    public boolean isConfigured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
