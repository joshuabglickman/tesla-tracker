package com.nextrayvision.teslatracking.tesla;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.TeslaAccount;
import com.nextrayvision.teslatracking.model.TeslaAccountRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Tesla OAuth: account linking, token refresh, and one-time partner registration. */
@Service
public class TeslaAuthService {

    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final TeslaProperties props;
    private final TeslaAccountRepository accounts;
    private final RestClient http = RestClient.create();

    public TeslaAuthService(TeslaProperties props, TeslaAccountRepository accounts) {
        this.props = props;
        this.accounts = accounts;
    }

    public Optional<TeslaAccount> account() {
        return accounts.findById(TeslaAccount.ID);
    }

    public String fleetApiBaseUrl() {
        return account().map(TeslaAccount::getFleetApiBaseUrl).orElse(props.fleetApiBaseUrl());
    }

    public String authorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(props.authorizeUrl())
                .queryParam("response_type", "code")
                .queryParam("client_id", props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("scope", props.scopes())
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    public void exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code", code);
        form.add("audience", props.fleetApiBaseUrl());
        form.add("redirect_uri", props.redirectUri());
        JsonNode token = tokenRequest(form);

        TeslaAccount account = account().orElseGet(TeslaAccount::new);
        account.setLinkedAt(Instant.now());
        account.setFleetApiBaseUrl(props.fleetApiBaseUrl());
        store(account, token);
    }

    /** Returns a valid access token, refreshing it first if it is about to expire. */
    public synchronized String accessToken() {
        TeslaAccount account = account().orElseThrow(() -> new IllegalStateException("No Tesla account linked"));
        if (account.getExpiresAt() == null || Instant.now().plus(REFRESH_MARGIN).isAfter(account.getExpiresAt())) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("client_id", props.clientId());
            form.add("refresh_token", account.getRefreshToken());
            // Tesla refresh tokens are single use, so the new one must be saved right away.
            store(account, tokenRequest(form));
        }
        return account.getAccessToken();
    }

    public void setFleetApiBaseUrl(String url) {
        account().ifPresent(account -> {
            account.setFleetApiBaseUrl(url);
            accounts.save(account);
        });
    }

    public void unlink() {
        accounts.deleteAll();
    }

    /**
     * One-time registration of this app with the Fleet API for the configured region.
     * Tesla checks that the public key is reachable at https://domain/.well-known/appspecific/com.tesla.3p.public-key.pem.
     */
    public JsonNode registerPartnerAccount(String domain) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("scope", "openid vehicle_device_data");
        form.add("audience", props.fleetApiBaseUrl());
        String partnerToken = tokenRequest(form).path("access_token").asText();

        return http.post()
                .uri(props.fleetApiBaseUrl() + "/api/1/partner_accounts")
                .header("Authorization", "Bearer " + partnerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domain", domain))
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode tokenRequest(MultiValueMap<String, String> form) {
        return http.post()
                .uri(props.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
    }

    private void store(TeslaAccount account, JsonNode token) {
        account.setAccessToken(token.path("access_token").asText());
        if (token.hasNonNull("refresh_token")) {
            account.setRefreshToken(token.path("refresh_token").asText());
        }
        account.setExpiresAt(Instant.now().plusSeconds(token.path("expires_in").asLong(3600)));
        accounts.save(account);
    }
}
