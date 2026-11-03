package com.nextrayvision.teslatracking.places;

import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Names Home and Work without being told. Tesla doesn't share the car's saved Home and Work addresses,
 * but when the car navigates to one of them it reports that name and its position. Home is also inferred
 * from where the car spends its nights. Neither overrides a place you named yourself.
 */
@Service
public class HomeDetector {

    private static final Logger log = LoggerFactory.getLogger(HomeDetector.class);
    private static final double RADIUS_METERS = 150;
    private static final LocalTime NIGHT = LocalTime.of(3, 0);
    private static final int MIN_NIGHTS = 3;
    private static final double MAX_MILES_OVERNIGHT = 0.1;

    private final SavedPlaceRepository savedPlaces;
    private final VehicleRepository vehicles;
    private final OdometerReadingRepository readings;

    public HomeDetector(SavedPlaceRepository savedPlaces, VehicleRepository vehicles, OdometerReadingRepository readings) {
        this.savedPlaces = savedPlaces;
        this.vehicles = vehicles;
        this.readings = readings;
    }

    public record Spot(double lat, double lon, int nights) {
    }

    /** Saves the car's own "Home" or "Work" favorite the first time it navigates there. */
    public void learnFromNavigation(String destinationName, Double latitude, Double longitude) {
        if (destinationName == null || latitude == null || longitude == null) {
            return;
        }
        String name = destinationName.trim();
        if (name.equalsIgnoreCase("Home") || name.equalsIgnoreCase("Work")) {
            save(name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase(), latitude, longitude);
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void detectHomeFromNights() {
        for (Vehicle v : vehicles.findAll()) {
            overnightSpot(readings.findByVinOrderByRecordedAtAsc(v.getVin()), ZoneId.systemDefault())
                    .ifPresent(spot -> save("Home", spot.lat(), spot.lon()));
        }
    }

    private void save(String name, double latitude, double longitude) {
        List<SavedPlace> existing = savedPlaces.findAll();
        boolean known = existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name)
                || PlaceService.distanceMeters(latitude, longitude, p.getLatitude(), p.getLongitude()) <= p.getRadiusMeters());
        if (!known) {
            savedPlaces.save(new SavedPlace(name, latitude, longitude, RADIUS_METERS));
            log.info("Learned {} at {}, {}", name, latitude, longitude);
        }
    }

    /**
     * Where the car was parked at 3 a.m. on most nights: the spot with at least {@value #MIN_NIGHTS} nights
     * that also accounts for at least half of all nights seen.
     *
     * @param readings one vehicle's readings in ascending time order
     */
    public static Optional<Spot> overnightSpot(List<OdometerReading> readings, ZoneId zone) {
        if (readings.isEmpty()) {
            return Optional.empty();
        }
        List<double[]> nights = new ArrayList<>();
        LocalDate first = readings.getFirst().getRecordedAt().atZone(zone).toLocalDate().plusDays(1);
        LocalDate last = readings.getLast().getRecordedAt().atZone(zone).toLocalDate();
        int i = 0;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            var at = day.atTime(NIGHT).atZone(zone).toInstant();
            while (i + 1 < readings.size() && !readings.get(i + 1).getRecordedAt().isAfter(at)) {
                i++;
            }
            OdometerReading before = readings.get(i);
            // Only count the night if the car is known to have stayed put: the next reading shows it
            // driving off (so it left after 3 a.m.) or no miles added.
            OdometerReading next = i + 1 < readings.size() ? readings.get(i + 1) : null;
            boolean stayed = next != null && (next.isDriving()
                    || next.getOdometerMiles() - before.getOdometerMiles() < MAX_MILES_OVERNIGHT);
            if (!before.getRecordedAt().isAfter(at) && before.hasLocation() && !before.isDriving() && stayed) {
                nights.add(new double[] {before.getLatitude(), before.getLongitude()});
            }
        }

        List<List<double[]>> clusters = new ArrayList<>();
        for (double[] p : nights) {
            clusters.stream()
                    .filter(c -> PlaceService.distanceMeters(p[0], p[1], c.getFirst()[0], c.getFirst()[1]) <= RADIUS_METERS)
                    .findFirst()
                    .ifPresentOrElse(c -> c.add(p), () -> clusters.add(new ArrayList<>(List.of(p))));
        }
        return clusters.stream()
                .max(Comparator.comparingInt(List::size))
                .filter(c -> c.size() >= MIN_NIGHTS && c.size() * 2 >= nights.size())
                .map(c -> new Spot(c.stream().mapToDouble(p -> p[0]).average().orElseThrow(),
                        c.stream().mapToDouble(p -> p[1]).average().orElseThrow(), c.size()));
    }
}
