package com.nextrayvision.teslatracking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

@Entity
public class Vehicle {

    @Id
    private String vin;

    private String displayName;

    /** online, asleep or offline, as last reported by the vehicle list. */
    private String state;

    /** P, D, R, N, or null when parked and powered down. */
    private String shiftState;

    private Double odometerMiles;

    private Double latitude;

    private Double longitude;

    private Instant lastSeenAt;

    /** Last time vehicle_data was fetched (the call that returns the odometer). */
    private Instant lastDataAt;

    protected Vehicle() {
    }

    public Vehicle(String vin) {
        this.vin = vin;
    }

    public static boolean isDrivingGear(String shiftState) {
        return "D".equals(shiftState) || "R".equals(shiftState) || "N".equals(shiftState);
    }

    public boolean isDriving() {
        return isDrivingGear(shiftState);
    }

    public String getVin() { return vin; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getShiftState() { return shiftState; }
    public void setShiftState(String shiftState) { this.shiftState = shiftState; }
    public Double getOdometerMiles() { return odometerMiles; }
    public void setOdometerMiles(Double odometerMiles) { this.odometerMiles = odometerMiles; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getLastDataAt() { return lastDataAt; }
    public void setLastDataAt(Instant lastDataAt) { this.lastDataAt = lastDataAt; }
}
