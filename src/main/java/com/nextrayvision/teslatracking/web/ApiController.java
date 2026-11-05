package com.nextrayvision.teslatracking.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import com.nextrayvision.teslatracking.places.GeocodingService;
import com.nextrayvision.teslatracking.places.PlaceService;
import com.nextrayvision.teslatracking.places.PlaceService.PlaceLabel;
import com.nextrayvision.teslatracking.places.SavedPlace;
import com.nextrayvision.teslatracking.places.SavedPlaceRepository;
import com.nextrayvision.teslatracking.tesla.KeyService;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import com.nextrayvision.teslatracking.tracking.DriveDetector;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Destination;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Drive;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Point;
import com.nextrayvision.teslatracking.tracking.MileageCalculator;
import com.nextrayvision.teslatracking.tracking.PollingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final int DAILY_HISTORY_DAYS = 60;
    private static final int MONTHLY_HISTORY_MONTHS = 12;
    private static final int RECENT_DRIVES = 25;
    private static final double DEFAULT_PLACE_RADIUS_METERS = 150;
    private static final double MIN_PLACE_RADIUS_METERS = 10;
    private static final double MAX_PLACE_RADIUS_METERS = 5_000;

    private final TeslaProperties props;
    private final TeslaAuthService auth;
    private final KeyService keys;
    private final PollingService polling;
    private final VehicleRepository vehicles;
    private final OdometerReadingRepository readings;
    private final PlaceService places;
    private final SavedPlaceRepository savedPlaces;
    private final GeocodingService geocoding;

    public ApiController(TeslaProperties props, TeslaAuthService auth, KeyService keys, PollingService polling,
                         VehicleRepository vehicles, OdometerReadingRepository readings,
                         PlaceService places, SavedPlaceRepository savedPlaces, GeocodingService geocoding) {
        this.props = props;
        this.auth = auth;
        this.keys = keys;
        this.polling = polling;
        this.vehicles = vehicles;
        this.readings = readings;
        this.places = places;
        this.savedPlaces = savedPlaces;
        this.geocoding = geocoding;
    }

    public record Status(boolean configured, boolean linked, String redirectUri, String fleetApiBaseUrl,
                         Instant linkedAt, List<String> grantedScopes, Instant lastPollAt, String lastError,
                         String publicKeyPem) {
    }

    public record DayMiles(LocalDate date, double miles) {
    }

    public record MonthMiles(YearMonth month, double miles) {
    }

    public record VehicleSummary(String vin, String name, String state, String shiftState, Double odometerMiles,
                                 Double latitude, Double longitude, PlaceLabel place,
                                 Instant lastSeenAt, Instant lastDataAt,
                                 double today, double last7Days, double last30Days, double yearToDate,
                                 double tracked, LocalDate trackingSince,
                                 List<DayMiles> daily, List<MonthMiles> monthly, List<DriveView> recentDrives) {
    }

    public record DriveView(Instant startedAt, Instant endedAt, boolean approximate, double miles,
                            Double startLat, Double startLon, PlaceLabel startPlace,
                            Double endLat, Double endLon, PlaceLabel endPlace,
                            Destination destination, List<Point> path) {

        /** When the drive happened: its start, or the middle of the range when only the range is known. */
        Instant when() {
            return approximate && endedAt != null
                    ? startedAt.plus(Duration.between(startedAt, endedAt).dividedBy(2))
                    : startedAt;
        }

        /** A drive that stops within this distance of its navigation destination arrived there. */
        static final double ARRIVAL_METERS = 300;

        /**
         * Labels each drive in order. A drive that starts where the previous one ended starts from the place
         * that one arrived at, so a navigation destination's name is kept for the trip back.
         */
        static List<DriveView> all(List<Drive> drives, PlaceService.Labeler labeler) {
            List<DriveView> views = new ArrayList<>();
            DriveView previous = null;
            for (Drive d : drives) {
                DriveView view = of(d, labeler);
                if (previous != null) {
                    view = view.departingFrom(previous);
                }
                views.add(view);
                previous = view;
            }
            return views;
        }

        DriveView departingFrom(DriveView previous) {
            if (startPlace == null || startPlace.savedPlaceId() != null || previous.endPlace() == null
                    || previous.endLat() == null || previous.endLon() == null
                    || PlaceService.distanceMeters(startLat, startLon, previous.endLat(), previous.endLon()) > ARRIVAL_METERS) {
                return this;
            }
            return new DriveView(startedAt, endedAt, approximate, miles, startLat, startLon, previous.endPlace(),
                    endLat, endLon, endPlace, destination, path);
        }

        static DriveView of(Drive d, PlaceService.Labeler labeler) {
            return new DriveView(d.startedAt(), d.endedAt(), d.approximate(), d.miles(),
                    d.startLat(), d.startLon(), labeler.label(d.startLat(), d.startLon()),
                    d.endLat(), d.endLon(), arrivedAt(d, labeler), d.destination(), d.path());
        }

        /** Where the drive ended, named after the navigation destination and, if you saved it, your place for it. */
        private static PlaceLabel arrivedAt(Drive d, PlaceService.Labeler labeler) {
            PlaceLabel end = arrival(labeler.label(d.endLat(), d.endLon()), d.destination(), d.endLat(), d.endLon());
            if (end == null || end.savedPlaceId() != null || end.name() == null) {
                return end;
            }
            PlaceLabel mine = labeler.savedNamed(end.name(), d.endLat(), d.endLon());
            return mine == null ? end
                    : new PlaceLabel(mine.name(), mine.address() != null ? mine.address() : end.address(), mine.savedPlaceId());
        }

        /**
         * Names the end of a drive after the destination you navigated to, when the car stopped there.
         * Tesla reports it the way the car shows it, either "Giant" or "Giant, 1020 W 41st St, Baltimore, MD 21211";
         * without an address part, the looked-up address is kept. A place you named yourself still wins.
         */
        static PlaceLabel arrival(PlaceLabel end, Destination dest, Double endLat, Double endLon) {
            if (end == null || end.savedPlaceId() != null || dest == null || dest.name() == null || dest.name().isBlank()
                    || dest.lat() == null || dest.lon() == null || endLat == null || endLon == null
                    || PlaceService.distanceMeters(endLat, endLon, dest.lat(), dest.lon()) > ARRIVAL_METERS) {
                return end;
            }
            String[] parts = dest.name().split(",\s*", 2);
            return new PlaceLabel(parts[0].trim(), parts.length > 1 ? parts[1].trim() : end.address(), null);
        }
    }

    public record NewPlace(String name, String address, Double latitude, Double longitude, Double radiusMeters) {
    }

    public record PartnerRequest(String domain) {
    }

    @GetMapping("/status")
    public Status status() {
        var account = auth.account();
        return new Status(props.isConfigured(), account.isPresent(), props.redirectUri(), auth.fleetApiBaseUrl(),
                account.map(a -> a.getLinkedAt()).orElse(null), auth.grantedScopes(),
                polling.lastPollAt(), polling.lastError(),
                keys.publicKeyPem());
    }

    @GetMapping("/summary")
    public List<VehicleSummary> summary() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<VehicleSummary> result = new ArrayList<>();
        PlaceService.Labeler labeler = places.labeler();
        for (Vehicle v : vehicles.findAll()) {
            List<OdometerReading> history = readings.findByVinOrderByRecordedAtAsc(v.getVin());
            TreeMap<LocalDate, Double> days = MileageCalculator.milesPerDay(history, zone);

            List<DayMiles> daily = new ArrayList<>();
            for (LocalDate d = today.minusDays(DAILY_HISTORY_DAYS - 1); !d.isAfter(today); d = d.plusDays(1)) {
                daily.add(new DayMiles(d, days.getOrDefault(d, 0.0)));
            }
            List<MonthMiles> monthly = new ArrayList<>();
            YearMonth thisMonth = YearMonth.from(today);
            for (int i = MONTHLY_HISTORY_MONTHS - 1; i >= 0; i--) {
                YearMonth m = thisMonth.minusMonths(i);
                monthly.add(new MonthMiles(m, MileageCalculator.milesBetween(days, m.atDay(1), m.atEndOfMonth())));
            }

            double tracked = history.isEmpty() ? 0
                    : history.getLast().getOdometerMiles() - history.getFirst().getOdometerMiles();
            LocalDate since = history.isEmpty() ? null : history.getFirst().getRecordedAt().atZone(zone).toLocalDate();

            result.add(new VehicleSummary(v.getVin(), v.getDisplayName(), v.getState(), v.getShiftState(),
                    v.getOdometerMiles(), v.getLatitude(), v.getLongitude(),
                    labeler.label(v.getLatitude(), v.getLongitude()), v.getLastSeenAt(), v.getLastDataAt(),
                    days.getOrDefault(today, 0.0),
                    MileageCalculator.milesBetween(days, today.minusDays(6), today),
                    MileageCalculator.milesBetween(days, today.minusDays(29), today),
                    MileageCalculator.milesBetween(days, today.withDayOfYear(1), today),
                    Math.max(0, tracked), since, daily, monthly, recentDrives(history, labeler)));
        }
        return result;
    }

    private static List<DriveView> recentDrives(List<OdometerReading> history, PlaceService.Labeler labeler) {
        List<DriveView> drives = new ArrayList<>(DriveView.all(detectDrives(history, labeler), labeler));
        Collections.reverse(drives);
        return drives.subList(0, Math.min(RECENT_DRIVES, drives.size()));
    }

    private static List<Drive> detectDrives(List<OdometerReading> history, PlaceService.Labeler labeler) {
        return DriveDetector.detect(history,
                r -> r.hasLocation() ? labeler.savedPlaceAt(r.getLatitude(), r.getLongitude()) : null);
    }

    @GetMapping("/places")
    public List<SavedPlace> listPlaces() {
        return savedPlaces.findAll();
    }

    /** Adds a place at a position, or, when none is given, at the position of its address. */
    @PostMapping("/places")
    public ResponseEntity<?> addPlace(@RequestBody NewPlace request) {
        String name = trimmed(request.name());
        String address = trimmed(request.address());
        if (name == null) {
            return error("A place needs a name.");
        }
        if (!validRadius(request.radiusMeters())) {
            return error(radiusError());
        }
        Double latitude = request.latitude();
        Double longitude = request.longitude();
        if (latitude == null || longitude == null) {
            if (address == null) {
                return error("Enter the place's address so it can be found on the map.");
            }
            try {
                Optional<double[]> found = geocoding.findPosition(address);
                if (found.isEmpty()) {
                    return error("Couldn't find \"" + address + "\" on the map. Try adding the city and state.");
                }
                latitude = found.get()[0];
                longitude = found.get()[1];
            } catch (RestClientException e) {
                return ResponseEntity.status(502).body(Map.of("error", "The address lookup failed. Try again in a minute."));
            }
        }
        double radius = request.radiusMeters() == null ? DEFAULT_PLACE_RADIUS_METERS : request.radiusMeters();
        return ResponseEntity.ok(savedPlaces.save(new SavedPlace(name, address, latitude, longitude, radius)));
    }

    /** Changes a place's name, address and radius; its position stays where it is. */
    @PutMapping("/places/{id}")
    public ResponseEntity<?> updatePlace(@PathVariable long id, @RequestBody NewPlace request) {
        Optional<SavedPlace> existing = savedPlaces.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String name = trimmed(request.name());
        if (name == null) {
            return error("A place needs a name.");
        }
        if (!validRadius(request.radiusMeters())) {
            return error(radiusError());
        }
        SavedPlace place = existing.get();
        place.update(name, trimmed(request.address()),
                request.radiusMeters() == null ? place.getRadiusMeters() : request.radiusMeters());
        return ResponseEntity.ok(savedPlaces.save(place));
    }

    private static boolean validRadius(Double radius) {
        return radius == null || (radius >= MIN_PLACE_RADIUS_METERS && radius <= MAX_PLACE_RADIUS_METERS);
    }

    private static String radiusError() {
        return String.format("The radius must be between %.0f and %.0f meters.", MIN_PLACE_RADIUS_METERS, MAX_PLACE_RADIUS_METERS);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<Void> deletePlace(@PathVariable long id) {
        savedPlaces.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/poll")
    public Status pollNow() {
        polling.poll(true);
        return status();
    }

    @PostMapping("/partner-registration")
    public ResponseEntity<?> registerPartner(@RequestBody PartnerRequest request) {
        String domain = request.domain() == null ? "" : request.domain().trim()
                .replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (domain.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter the domain that hosts your public key."));
        }
        try {
            JsonNode response = auth.registerPartnerAccount(domain);
            return ResponseEntity.ok(Map.of("ok", true, "domain", domain, "response", response));
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getStatusCode() + ": " + e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping(value = "/export/daily.csv", produces = "text/csv")
    public ResponseEntity<String> dailyCsv() {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder csv = new StringBuilder("vin,vehicle,date,miles\n");
        for (Vehicle v : vehicles.findAll()) {
            MileageCalculator.milesPerDay(readings.findByVinOrderByRecordedAtAsc(v.getVin()), zone)
                    .forEach((day, miles) -> csv.append(v.getVin()).append(',').append(csvText(v.getDisplayName()))
                            .append(',').append(day).append(',').append(String.format("%.2f", miles)).append('\n'));
        }
        return attachment("tesla-daily-miles.csv", csv.toString());
    }

    @GetMapping(value = "/export/readings.csv", produces = "text/csv")
    public ResponseEntity<String> readingsCsv() {
        StringBuilder csv = new StringBuilder("vin,recorded_at,odometer_miles,gear,latitude,longitude,speed_mph\n");
        for (Vehicle v : vehicles.findAll()) {
            for (OdometerReading r : readings.findByVinOrderByRecordedAtAsc(v.getVin())) {
                csv.append(r.getVin()).append(',').append(r.getRecordedAt()).append(',')
                        .append(String.format("%.2f", r.getOdometerMiles())).append(',')
                        .append(r.getShiftState() == null ? "" : r.getShiftState()).append(',')
                        .append(csvNumber(r.getLatitude())).append(',').append(csvNumber(r.getLongitude())).append(',')
                        .append(csvNumber(r.getSpeedMph())).append('\n');
            }
        }
        return attachment("tesla-odometer-readings.csv", csv.toString());
    }

    @GetMapping(value = "/export/drives.csv", produces = "text/csv")
    public ResponseEntity<String> drivesCsv() {
        StringBuilder csv = new StringBuilder(
                "date,time,miles,from,to,vin,vehicle,started_at,ended_at,times_approximate,"
                        + "start_name,start_address,start_latitude,start_longitude,"
                        + "end_name,end_address,end_latitude,end_longitude,"
                        + "navigation_destination,navigation_latitude,navigation_longitude\n");
        PlaceService.Labeler labeler = places.labeler();
        ZoneId zone = ZoneId.systemDefault();
        for (Vehicle v : vehicles.findAll()) {
            for (DriveView d : DriveView.all(detectDrives(readings.findByVinOrderByRecordedAtAsc(v.getVin()), labeler), labeler)) {
                ZonedDateTime when = d.when().atZone(zone);
                csv.append(when.toLocalDate()).append(',')
                        .append(when.toLocalTime().truncatedTo(ChronoUnit.MINUTES)).append(',')
                        .append(String.format("%.2f", d.miles())).append(',')
                        .append(csvText(fullPlace(d.startPlace()))).append(',')
                        .append(csvText(d.endedAt() == null ? "" : fullPlace(d.endPlace()))).append(',')
                        .append(v.getVin()).append(',').append(csvText(v.getDisplayName())).append(',')
                        .append(d.startedAt()).append(',').append(d.endedAt() == null ? "" : d.endedAt()).append(',').append(d.approximate()).append(',')
                        .append(csvPlace(d.startPlace())).append(',')
                        .append(csvNumber(d.startLat())).append(',').append(csvNumber(d.startLon())).append(',')
                        .append(csvPlace(d.endPlace())).append(',')
                        .append(csvNumber(d.endLat())).append(',').append(csvNumber(d.endLon())).append(',')
                        .append(csvDestination(d.destination())).append('\n');
            }
        }
        return attachment("tesla-drives.csv", csv.toString());
    }

    private static String csvDestination(Destination destination) {
        return destination == null ? ",," : csvText(destination.name()) + ','
                + csvNumber(destination.lat()) + ',' + csvNumber(destination.lon());
    }

    /** "Giant, 1020 W 41st St, Baltimore, MD 21211": the name and address together, or whichever is known. */
    static String fullPlace(PlaceLabel place) {
        if (place == null) {
            return "";
        }
        return Stream.of(place.name(), place.address())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
    }

    private static String csvPlace(PlaceLabel place) {
        return place == null ? "," : csvText(place.name()) + ',' + csvText(place.address());
    }

    private static String csvNumber(Double value) {
        return value == null ? "" : value.toString();
    }

    private static String csvText(String value) {
        return value == null ? "" : '"' + value.replace("\"", "\"\"") + '"';
    }

    private static ResponseEntity<String> attachment(String filename, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
