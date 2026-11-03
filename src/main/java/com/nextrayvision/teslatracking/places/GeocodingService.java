package com.nextrayvision.teslatracking.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Looks up street addresses for parked positions using OpenStreetMap's Nominatim service.
 * Lookups are queued and sent one at a time, well under Nominatim's limit of one request per second,
 * and every result is cached so each spot is only ever looked up once.
 * <p>
 * Nominatim names the single closest feature, which in a parking lot is often the lot itself or an EV
 * charger in it. In that case the nearest business is found with Overpass and used instead, so a spot
 * in the Giant parking lot reads "Giant, 1020 W 41st St", not "Volta Charging".
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    /** Bump when lookups change, so cached results from the old lookup are redone. */
    static final int VERSION = 3;

    /** Features that are part of where you parked rather than where you went. */
    private static final Set<String> MINOR_TYPES = Set.of(
            "parking", "parking_space", "parking_entrance", "charging_station", "bicycle_parking",
            "motorcycle_parking", "atm", "vending_machine", "bench", "waste_basket", "waste_disposal",
            "recycling", "post_box", "telephone", "toilets", "drinking_water", "shelter", "parcel_locker");

    private final GeocodedPlaceRepository geocoded;
    private final OdometerReadingRepository readings;
    private final VehicleRepository vehicles;
    private final boolean enabled;
    private final String reverseUrl;
    private final String nearbyUrl;
    private final int nearbyRadiusMeters;
    private final RestClient http;

    /** Pending lookups by coordinate key, oldest first. */
    private final Map<String, double[]> queue = new LinkedHashMap<>();

    public GeocodingService(GeocodedPlaceRepository geocoded, OdometerReadingRepository readings, VehicleRepository vehicles,
                            @Value("${geocoding.enabled}") boolean enabled,
                            @Value("${geocoding.reverse-url}") String reverseUrl,
                            @Value("${geocoding.nearby-url}") String nearbyUrl,
                            @Value("${geocoding.nearby-radius-meters}") int nearbyRadiusMeters,
                            @Value("${geocoding.user-agent}") String userAgent) {
        this.geocoded = geocoded;
        this.readings = readings;
        this.vehicles = vehicles;
        this.enabled = enabled;
        this.reverseUrl = reverseUrl;
        this.nearbyUrl = nearbyUrl;
        this.nearbyRadiusMeters = nearbyRadiusMeters;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(10));
        timeouts.setReadTimeout(Duration.ofSeconds(40));
        // Nominatim's usage policy requires an identifying User-Agent.
        this.http = RestClient.builder().requestFactory(timeouts).defaultHeader("User-Agent", userAgent).build();
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
        if (isResolved(next.getKey())) {
            return;
        }
        try {
            geocoded.save(lookup(next.getKey(), next.getValue()[0], next.getValue()[1]));
        } catch (RestClientException e) {
            // Left uncached; the hourly backfill queues it again.
            log.warn("Address lookup failed for {}: {}", next.getKey(), e.getMessage());
        }
    }

    /** Queues every parked position with no up-to-date address, e.g. after an outage or on first start. */
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
            if (!isResolved(PlaceService.key(p[0], p[1]))) {
                enqueue(p[0], p[1]);
            }
        }
    }

    private boolean isResolved(String key) {
        return geocoded.findById(key).filter(p -> p.isCurrent(VERSION)).isPresent();
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
            return new GeocodedPlace(key, null, null, Instant.now(), VERSION);
        }
        int version = VERSION;
        if (isMinorFeature(result)) {
            try {
                Optional<JsonNode> business = nearbyBusiness(latitude, longitude);
                if (business.isPresent()) {
                    JsonNode tags = business.get().path("tags");
                    return new GeocodedPlace(key, tags.path("name").asText(), formatAddress(result, tags), Instant.now(), VERSION);
                }
            } catch (RestClientException e) {
                // Overpass is often busy. Show Nominatim's answer for now, but save it as out of date
                // so the hourly backfill tries the business lookup again.
                log.warn("Nearby business lookup failed for {}, will retry: {}", key, e.getMessage());
                version = VERSION - 1;
            }
        }
        return new GeocodedPlace(key, placeName(result), formatAddress(result), Instant.now(), version);
    }

    /** The nearest named business within the nearby radius, or empty if there is none. */
    private Optional<JsonNode> nearbyBusiness(double latitude, double longitude) {
        String query = String.format(Locale.ROOT, "[out:json][timeout:25];nwr(around:%d,%.6f,%.6f)[name]"
                + "[~\"^(shop|amenity|leisure|tourism|office|healthcare|craft)$\"~\".\"];out center tags;",
                nearbyRadiusMeters, latitude, longitude);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);
        JsonNode result = http.post().uri(nearbyUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        return result == null ? Optional.empty() : nearestBusiness(result, latitude, longitude);
    }

    static boolean isMinorFeature(JsonNode result) {
        return MINOR_TYPES.contains(result.path("type").asText(""));
    }

    static Optional<JsonNode> nearestBusiness(JsonNode overpass, double latitude, double longitude) {
        return StreamSupport.stream(overpass.path("elements").spliterator(), false)
                .filter(e -> !e.path("tags").path("name").asText("").isBlank())
                .filter(e -> !MINOR_TYPES.contains(e.path("tags").path("amenity").asText("")))
                .filter(e -> position(e) != null)
                .min(Comparator.comparingDouble(e -> {
                    double[] p = position(e);
                    return PlaceService.distanceMeters(latitude, longitude, p[0], p[1]);
                }));
    }

    private static double[] position(JsonNode element) {
        JsonNode at = element.has("center") ? element.path("center") : element;
        return at.path("lat").isNumber() && at.path("lon").isNumber()
                ? new double[]{at.path("lat").asDouble(), at.path("lon").asDouble()} : null;
    }

    static String placeName(JsonNode result) {
        String name = result.path("name").asText("").trim();
        String road = result.path("address").path("road").asText("");
        return name.isEmpty() || name.equals(road) ? null : name;
    }

    /** "123 Main St, Palo Alto, CA 94301" for US addresses, falling back to Nominatim's full display name. */
    static String formatAddress(JsonNode result) {
        JsonNode a = result.path("address");
        return formatAddress(result, a.path("house_number").asText(""), a.path("road").asText(""));
    }

    /** Uses a business's own street address when it has one, with the town and state from Nominatim. */
    static String formatAddress(JsonNode result, JsonNode businessTags) {
        String street = businessTags.path("addr:street").asText("");
        if (street.isBlank()) {
            return formatAddress(result);
        }
        return formatAddress(result, businessTags.path("addr:housenumber").asText(""), street);
    }

    private static String formatAddress(JsonNode result, String houseNumber, String road) {
        JsonNode a = result.path("address");
        boolean us = "us".equalsIgnoreCase(a.path("country_code").asText(""));
        String street = join(" ", houseNumber, us ? UsAddress.street(road) : road);
        String locality = first(a, "city", "town", "village", "hamlet", "suburb", "county");
        String state = a.path("state").asText("");
        String region = join(" ", us ? UsAddress.state(state) : state, a.path("postcode").asText(""));
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
