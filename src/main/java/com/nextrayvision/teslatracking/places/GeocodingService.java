package com.nextrayvision.teslatracking.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Looks up street addresses for parked positions using OpenStreetMap's Nominatim service.
 * Lookups are queued and sent one at a time, well under Nominatim's limit of one request per second,
 * and every result is cached so each spot is only ever looked up once.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private final GeocodedPlaceRepository geocoded;
    private final OdometerReadingRepository readings;
    private final VehicleRepository vehicles;
    private final boolean enabled;
    private final String reverseUrl;
    private final RestClient http;

    /** Pending lookups by coordinate key, oldest first. */
    private final Map<String, double[]> queue = new LinkedHashMap<>();

    public GeocodingService(GeocodedPlaceRepository geocoded, OdometerReadingRepository readings, VehicleRepository vehicles,
                            @Value("${geocoding.enabled}") boolean enabled,
                            @Value("${geocoding.reverse-url}") String reverseUrl,
                            @Value("${geocoding.user-agent}") String userAgent) {
        this.geocoded = geocoded;
        this.readings = readings;
        this.vehicles = vehicles;
        this.enabled = enabled;
        this.reverseUrl = reverseUrl;
        // Nominatim's usage policy requires an identifying User-Agent.
        this.http = RestClient.builder().defaultHeader("User-Agent", userAgent).build();
    }

    public void enqueue(double latitude, double longitude) {
        if (!enabled) {
            return;
        }
        synchronized (queue) {
            queue.putIfAbsent(PlaceService.key(latitude, longitude), new double[]{latitude, longitude});
        }
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 1_500)
    public void processNext() {
        Map.Entry<String, double[]> next;
        synchronized (queue) {
            Iterator<Map.Entry<String, double[]>> it = queue.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            next = it.next();
            it.remove();
        }
        if (geocoded.existsById(next.getKey())) {
            return;
        }
        try {
            geocoded.save(lookup(next.getKey(), next.getValue()[0], next.getValue()[1]));
        } catch (RestClientException e) {
            // Left uncached; the hourly backfill queues it again.
            log.warn("Address lookup failed for {}: {}", next.getKey(), e.getMessage());
        }
    }

    /** Queues every parked position that has no address yet, e.g. after an outage or on first start. */
    @Scheduled(initialDelay = 20_000, fixedDelay = 3_600_000)
    public void backfill() {
        List<double[]> points = new ArrayList<>();
        for (OdometerReading r : readings.findAll()) {
            if (r.hasLocation() && !r.isDriving()) {
                points.add(new double[]{r.getLatitude(), r.getLongitude()});
            }
        }
        for (Vehicle v : vehicles.findAll()) {
            if (v.getLatitude() != null && v.getLongitude() != null) {
                points.add(new double[]{v.getLatitude(), v.getLongitude()});
            }
        }
        for (double[] p : points) {
            if (!geocoded.existsById(PlaceService.key(p[0], p[1]))) {
                enqueue(p[0], p[1]);
            }
        }
    }

    private GeocodedPlace lookup(String key, double latitude, double longitude) {
        JsonNode result = http.get()
                .uri(UriComponentsBuilder.fromUriString(reverseUrl)
                        .queryParam("format", "jsonv2")
                        .queryParam("lat", latitude)
                        .queryParam("lon", longitude)
                        .queryParam("zoom", 18)
                        .queryParam("addressdetails", 1)
                        .queryParam("accept-language", Locale.getDefault().toLanguageTag())
                        .build().toUri())
                .retrieve()
                .body(JsonNode.class);
        // Positions with no address (e.g. open water) are cached as empty so they aren't retried.
        if (result == null || result.has("error")) {
            return new GeocodedPlace(key, null, null, Instant.now());
        }
        return new GeocodedPlace(key, placeName(result), formatAddress(result), Instant.now());
    }

    static String placeName(JsonNode result) {
        String name = result.path("name").asText("").trim();
        String road = result.path("address").path("road").asText("");
        return name.isEmpty() || name.equals(road) ? null : name;
    }

    /** "123 Main Street, Palo Alto, California 94301", falling back to Nominatim's full display name. */
    static String formatAddress(JsonNode result) {
        JsonNode a = result.path("address");
        String street = join(" ", a.path("house_number").asText(""), a.path("road").asText(""));
        String locality = first(a, "city", "town", "village", "hamlet", "suburb", "county");
        String region = join(" ", a.path("state").asText(""), a.path("postcode").asText(""));
        String formatted = join(", ", street, locality, region);
        return formatted.isEmpty() ? result.path("display_name").asText(null) : formatted;
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.trim());
            }
        }
        return String.join(separator, present);
    }
}
