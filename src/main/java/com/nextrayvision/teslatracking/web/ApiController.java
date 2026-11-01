package com.nextrayvision.teslatracking.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import com.nextrayvision.teslatracking.places.PlaceService;
import com.nextrayvision.teslatracking.places.PlaceService.PlaceLabel;
import com.nextrayvision.teslatracking.places.SavedPlace;
import com.nextrayvision.teslatracking.places.SavedPlaceRepository;
import com.nextrayvision.teslatracking.tesla.KeyService;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import com.nextrayvision.teslatracking.tracking.DriveDetector;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final int DAILY_HISTORY_DAYS = 60;
    private static final int MONTHLY_HISTORY_MONTHS = 12;
    private static final int RECENT_DRIVES = 25;
    private static final double DEFAULT_PLACE_RADIUS_METERS = 150;

    private final TeslaProperties props;
    private final TeslaAuthService auth;
    private final KeyService keys;
    private final PollingService polling;
    private final VehicleRepository vehicles;
    private final OdometerReadingRepository readings;
    private final PlaceService places;
    private final SavedPlaceRepository savedPlaces;

    public ApiController(TeslaProperties props, TeslaAuthService auth, KeyService keys, PollingService polling,
                         VehicleRepository vehicles, OdometerReadingRepository readings,
                         PlaceService places, SavedPlaceRepository savedPlaces) {
        this.props = props;
        this.auth = auth;
        this.keys = keys;
        this.polling = polling;
        this.vehicles = vehicles;
        this.readings = readings;
        this.places = places;
        this.savedPlaces = savedPlaces;
    }

    public record Status(boolean configured, boolean linked, String redirectUri, String fleetApiBaseUrl,
                         Instant linkedAt, Instant lastPollAt, String lastError, String publicKeyPem) {
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
                            Double endLat, Double endLon, PlaceLabel endPlace, List<Point> path) {

        static DriveView of(Drive d, PlaceService.Labeler labeler) {
            return new DriveView(d.startedAt(), d.endedAt(), d.approximate(), d.miles(),
                    d.startLat(), d.startLon(), labeler.label(d.startLat(), d.startLon()),
                    d.endLat(), d.endLon(), labeler.label(d.endLat(), d.endLon()), d.path());
        }
    }

    public record NewPlace(String name, Double latitude, Double longitude, Double radiusMeters) {
    }

    public record PartnerRequest(String domain) {
    }

    @GetMapping("/status")
    public Status status() {
        var account = auth.account();
        return new Status(props.isConfigured(), account.isPresent(), props.redirectUri(), auth.fleetApiBaseUrl(),
                account.map(a -> a.getLinkedAt()).orElse(null), polling.lastPollAt(), polling.lastError(),
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
        List<Drive> drives = new ArrayList<>(DriveDetector.detect(history));
        Collections.reverse(drives);
        return drives.subList(0, Math.min(RECENT_DRIVES, drives.size())).stream()
                .map(d -> DriveView.of(d, labeler))
                .toList();
    }

    @GetMapping("/places")
    public List<SavedPlace> listPlaces() {
        return savedPlaces.findAll();
    }

    @PostMapping("/places")
    public ResponseEntity<?> addPlace(@RequestBody NewPlace request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || request.latitude() == null || request.longitude() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A place needs a name and a position."));
        }
        double radius = request.radiusMeters() == null ? DEFAULT_PLACE_RADIUS_METERS : request.radiusMeters();
        return ResponseEntity.ok(savedPlaces.save(new SavedPlace(name, request.latitude(), request.longitude(), radius)));
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
                "vin,vehicle,started_at,ended_at,times_approximate,miles,"
                        + "start_name,start_address,start_latitude,start_longitude,"
                        + "end_name,end_address,end_latitude,end_longitude\n");
        PlaceService.Labeler labeler = places.labeler();
        for (Vehicle v : vehicles.findAll()) {
            for (Drive drive : DriveDetector.detect(readings.findByVinOrderByRecordedAtAsc(v.getVin()))) {
                DriveView d = DriveView.of(drive, labeler);
                csv.append(v.getVin()).append(',').append(csvText(v.getDisplayName())).append(',')
                        .append(d.startedAt()).append(',').append(d.endedAt() == null ? "" : d.endedAt()).append(',').append(d.approximate()).append(',')
                        .append(String.format("%.2f", d.miles())).append(',')
                        .append(csvPlace(d.startPlace())).append(',')
                        .append(csvNumber(d.startLat())).append(',').append(csvNumber(d.startLon())).append(',')
                        .append(csvPlace(d.endPlace())).append(',')
                        .append(csvNumber(d.endLat())).append(',').append(csvNumber(d.endLon())).append('\n');
            }
        }
        return attachment("tesla-drives.csv", csv.toString());
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
