package com.nextrayvision.teslatracking.tesla;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Optional;

/** Read-only Fleet API calls. */
@Component
public class TeslaApiClient {

    private final TeslaAuthService auth;
    private final RestClient http = RestClient.create();

    public TeslaApiClient(TeslaAuthService auth) {
        this.auth = auth;
    }

    /** Lists the account's vehicles with their online/asleep/offline state. Never wakes a car. */
    public JsonNode listVehicles() {
        return get("/api/1/vehicles").path("response");
    }

    /**
     * Fetches odometer, gear and GPS position for an online vehicle. Returns empty if the car has gone
     * to sleep or out of coverage since the vehicle list was read (HTTP 408).
     * Position is only included when the account granted the vehicle_location scope.
     */
    public Optional<JsonNode> vehicleData(String vin) {
        try {
            // The separators must be sent as %3B: Tesla drops a query parameter containing a raw ';'
            // and then returns its default data, which leaves out the location.
            return Optional.of(get("/api/1/vehicles/" + vin
                    + "/vehicle_data?endpoints=vehicle_state%3Bdrive_state%3Blocation_data")
                    .path("response"));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.REQUEST_TIMEOUT) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Tesla accounts live in one region; ask which so later calls go to the right host. */
    public String regionBaseUrl() {
        return get("/api/1/users/region").path("response").path("fleet_api_base_url").asText(null);
    }

    private JsonNode get(String path) {
        return http.get()
                .uri(URI.create(auth.fleetApiBaseUrl() + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .retrieve()
                .body(JsonNode.class);
    }
}
