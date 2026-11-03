package com.nextrayvision.teslatracking.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import com.nextrayvision.teslatracking.places.GeocodingService;
import com.nextrayvision.teslatracking.places.HomeDetector;
import com.nextrayvision.teslatracking.tesla.TeslaApiClient;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records odometer readings without disturbing the car:
 * <ul>
 *   <li>the vehicle list (which never wakes the car) is checked every poll interval;</li>
 *   <li>while a car is awake its odometer and position are read on every poll, so a drive is noticed
 *       within one poll interval;</li>
 *   <li>once it has sat parked and unchanged for a while, reads pause so it can fall asleep (see {@link ReadPacer}).</li>
 * </ul>
 * The odometer is cumulative, so if this app is off for a while the miles are picked up on the next reading.
 */
@Service
public class PollingService {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);
    private static final double MIN_CHANGE_MILES = 0.01;

    private final TeslaProperties props;
    private final TeslaAuthService auth;
    private final TeslaApiClient api;
    private final VehicleRepository vehicles;
    private final OdometerReadingRepository readings;
    private final GeocodingService geocoding;
    private final HomeDetector homeDetector;

    private final Map<String, ReadPacer> pacers = new ConcurrentHashMap<>();

    private volatile Instant lastPollAt;
    private volatile String lastError;

    public PollingService(TeslaProperties props, TeslaAuthService auth, TeslaApiClient api,
                          VehicleRepository vehicles, OdometerReadingRepository readings, GeocodingService geocoding,
                          HomeDetector homeDetector) {
        this.props = props;
        this.auth = auth;
        this.api = api;
        this.vehicles = vehicles;
        this.readings = readings;
        this.geocoding = geocoding;
        this.homeDetector = homeDetector;
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${tesla.poll-interval}")
    public void scheduledPoll() {
        if (props.isConfigured() && auth.account().isPresent()) {
            poll(false);
        }
    }

    /** @param force read the odometer of every online car regardless of the parked back-off */
    public synchronized void poll(boolean force) {
        Instant now = Instant.now();
        try {
            for (JsonNode node : api.listVehicles()) {
                String vin = node.path("vin").asText();
                Vehicle vehicle = vehicles.findById(vin).orElseGet(() -> new Vehicle(vin));
                String previousState = vehicle.getState();
                vehicle.setDisplayName(text(node, "display_name"));
                vehicle.setState(text(node, "state"));
                vehicle.setLastSeenAt(now);

                ReadPacer pacer = pacers.computeIfAbsent(vin,
                        v -> new ReadPacer(props.idleBeforeSleepPause(), props.sleepPause()));
                boolean justWoke = !"online".equals(previousState);
                if ("online".equals(vehicle.getState())
                        && (pacer.shouldRead(now, justWoke || force, vehicle.isDriving()))) {
                    Double milesBefore = vehicle.getOdometerMiles();
                    if (readOdometer(vehicle, now)) {
                        boolean moved = milesBefore == null
                                || Math.abs(vehicle.getOdometerMiles() - milesBefore) >= MIN_CHANGE_MILES;
                        pacer.afterRead(now, vehicle.isDriving(), moved);
                    }
                }
                vehicles.save(vehicle);
            }
            lastError = null;
        } catch (RuntimeException e) {
            log.warn("Tesla poll failed", e);
            lastError = e.getMessage();
        } finally {
            lastPollAt = now;
        }
    }

    /** @return false if the car had gone to sleep and nothing was read */
    private boolean readOdometer(Vehicle vehicle, Instant now) {
        Optional<JsonNode> data = api.vehicleData(vehicle.getVin());
        if (data.isEmpty()) {
            return false;
        }
        JsonNode odometer = data.get().path("vehicle_state").path("odometer");
        JsonNode drive = data.get().path("drive_state");
        String shiftState = text(drive, "shift_state");
        Double latitude = number(drive, "latitude");
        Double longitude = number(drive, "longitude");
        String destinationName = text(drive, "active_route_destination");
        Double destinationLatitude = number(drive, "active_route_latitude");
        Double destinationLongitude = number(drive, "active_route_longitude");
        homeDetector.learnFromNavigation(destinationName, destinationLatitude, destinationLongitude);
        vehicle.setShiftState(shiftState);
        vehicle.setLastDataAt(now);
        if (latitude != null && longitude != null) {
            vehicle.setLatitude(latitude);
            vehicle.setLongitude(longitude);
        }
        if (!odometer.isNumber()) {
            return true;
        }
        double miles = odometer.asDouble();
        vehicle.setOdometerMiles(miles);

        // Save when the car has moved, when it starts or stops driving so each drive's
        // start and end points are captured, when a navigation route is set or changed, and when the
        // position first becomes known, so the next drive has a starting point.
        boolean changed = readings.findTopByVinOrderByRecordedAtDesc(vehicle.getVin())
                .map(last -> Math.abs(miles - last.getOdometerMiles()) >= MIN_CHANGE_MILES
                        || last.isDriving() != Vehicle.isDrivingGear(shiftState)
                        || !Objects.equals(last.getDestinationName(), destinationName)
                        || (!last.hasLocation() && latitude != null && longitude != null))
                .orElse(true);
        if (changed) {
            readings.save(new OdometerReading(vehicle.getVin(), now, miles, shiftState,
                    latitude, longitude, number(drive, "speed"))
                    .withDestination(destinationName, destinationLatitude, destinationLongitude));
        }
        if (latitude != null && longitude != null && !Vehicle.isDrivingGear(shiftState)) {
            geocoding.enqueue(latitude, longitude);
        }
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    public Instant lastPollAt() {
        return lastPollAt;
    }

    public String lastError() {
        return lastError;
    }
}
