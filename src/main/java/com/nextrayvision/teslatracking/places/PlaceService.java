package com.nextrayvision.teslatracking.places;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turns coordinates into a name and address, preferring places you named yourself. */
@Service
public class PlaceService {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final SavedPlaceRepository savedPlaces;
    private final GeocodedPlaceRepository geocoded;
    private final GeocodingService geocoding;

    public PlaceService(SavedPlaceRepository savedPlaces, GeocodedPlaceRepository geocoded, GeocodingService geocoding) {
        this.savedPlaces = savedPlaces;
        this.geocoded = geocoded;
        this.geocoding = geocoding;
    }

    /**
     * @param name your name for the place if it is one you saved, otherwise the map's name for it (may be null)
     * @param address street address, or null while it is still being looked up
     * @param savedPlaceId set when the name comes from one of your saved places
     */
    public record PlaceLabel(String name, String address, Long savedPlaceId) {
    }

    /** Rounds to 4 decimal places (about 10 m) so nearby parking spots share one lookup. */
    public static String key(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude);
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    /**
     * Returns a labeler that reuses one snapshot of saved places and cached addresses, for labelling
     * many points at once. Points with no cached address are queued for lookup and labelled once it completes.
     */
    public Labeler labeler() {
        List<SavedPlace> saved = savedPlaces.findAll();
        Map<String, GeocodedPlace> cache = geocoded.findAll().stream()
                .collect(Collectors.toMap(GeocodedPlace::getCoordKey, Function.identity()));
        return (latitude, longitude) -> {
            if (latitude == null || longitude == null) {
                return null;
            }
            Optional<SavedPlace> mine = saved.stream()
                    .filter(p -> distanceMeters(latitude, longitude, p.getLatitude(), p.getLongitude()) <= p.getRadiusMeters())
                    .min(Comparator.comparingDouble(p -> distanceMeters(latitude, longitude, p.getLatitude(), p.getLongitude())));
            GeocodedPlace found = cache.get(key(latitude, longitude));
            if (found == null) {
                geocoding.enqueue(latitude, longitude);
            }
            String name = mine.map(SavedPlace::getName).orElse(found == null ? null : found.getName());
            String address = found == null ? null : found.getAddress();
            return new PlaceLabel(name, address, mine.map(SavedPlace::getId).orElse(null));
        };
    }

    @FunctionalInterface
    public interface Labeler {
        PlaceLabel label(Double latitude, Double longitude);
    }
}
