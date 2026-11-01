package com.nextrayvision.teslatracking.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.Vehicle;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import com.nextrayvision.teslatracking.places.GeocodingService;
import com.nextrayvision.teslatracking.tesla.TeslaApiClient;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Records odometer readings without disturbing the car:
 * <ul>
 *   <li>the vehicle list (which never wakes the car) is checked every poll interval;</li>
 *   <li>the odometer is read when a car comes online and on every poll while it is driving;</li>
 *   <li>while a car is online but parked it is read at most once per parked interval, so it can fall asleep.</li>
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

    private volatile Instant lastPollAt;
    private volatile String lastError;

    public PollingService(TeslaProperties props, TeslaAuthService auth, TeslaApiClient api,
                          VehicleRepository vehicles, OdometerReadingRepository readings, GeocodingService geocoding) {
        this.props = props;
        this.auth = auth;
        this.api = api;
        this.vehicles = vehicles;
        this.readings = readings;
        this.geocoding = geocoding;
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

                if ("online".equals(vehicle.getState()) && (force || shouldReadOdometer(vehicle, previousState, now))) {
                    readOdometer(vehicle, now);
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

    private boolean shouldReadOdometer(Vehicle vehicle, String previousState, Instant now) {
        if (vehicle.getLastDataAt() == null || !"online".equals(previousState) || vehicle.isDriving()) {
            return true;
        }
        return !now.isBefore(vehicle.getLastDataAt().plus(props.parkedPollInterval()));
    }

    private void readOdometer(Vehicle vehicle, Instant now) {
        Optional<JsonNode> data = api.vehicleData(vehicle.getVin());
        if (data.isEmpty()) {
            return;
        }
        JsonNode odometer = data.get().path("vehicle_state").path("odometer");
        JsonNode drive = data.get().path("drive_state");
        String shiftState = text(drive, "shift_state");
        Double latitude = number(drive, "latitude");
        Double longitude = number(drive, "longitude");
        vehicle.setShiftState(shiftState);
        vehicle.setLastDataAt(now);
        if (latitude != null && longitude != null) {
            vehicle.setLatitude(latitude);
            vehicle.setLongitude(longitude);
        }
        if (!odometer.isNumber()) {
            return;
        }
        double miles = odometer.asDouble();
        vehicle.setOdometerMiles(miles);

        // Save when the car has moved, and when it starts or stops driving so each drive's
        // start and end points are captured.
        boolean changed = readings.findTopByVinOrderByRecordedAtDesc(vehicle.getVin())
                .map(last -> Math.abs(miles - last.getOdometerMiles()) >= MIN_CHANGE_MILES
                        || last.isDriving() != Vehicle.isDrivingGear(shiftState))
                .orElse(true);
        if (changed) {
            readings.save(new OdometerReading(vehicle.getVin(), now, miles, shiftState,
                    latitude, longitude, number(drive, "speed")));
        }
        if (latitude != null && longitude != null && !Vehicle.isDrivingGear(shiftState)) {
            geocoding.enqueue(latitude, longitude);
        }
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
