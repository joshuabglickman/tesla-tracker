package com.nextrayvision.teslatracking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** One snapshot of a vehicle: odometer, gear, GPS position and navigation destination at a point in time. */
@Entity
@Table(indexes = @Index(columnList = "vin, recordedAt"))
public class OdometerReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vin;

    private Instant recordedAt;

    private double odometerMiles;

    /** P, D, R, N, or null when parked and powered down. */
    private String shiftState;

    private Double latitude;

    private Double longitude;

    private Double speedMph;

    /** Where the car's navigation was routing to, or null when no route was active. */
    private String destinationName;

    private Double destinationLatitude;

    private Double destinationLongitude;

    protected OdometerReading() {
    }

    public OdometerReading(String vin, Instant recordedAt, double odometerMiles, String shiftState,
                           Double latitude, Double longitude, Double speedMph) {
        this.vin = vin;
        this.recordedAt = recordedAt;
        this.odometerMiles = odometerMiles;
        this.shiftState = shiftState;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedMph = speedMph;
    }

    public boolean isDriving() {
        return Vehicle.isDrivingGear(shiftState);
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }

    public boolean hasDestination() {
        return destinationName != null || destinationLatitude != null;
    }

    public OdometerReading withDestination(String name, Double latitude, Double longitude) {
        this.destinationName = name;
        this.destinationLatitude = latitude;
        this.destinationLongitude = longitude;
        return this;
    }

    public Long getId() { return id; }
    public String getVin() { return vin; }
    public Instant getRecordedAt() { return recordedAt; }
    public double getOdometerMiles() { return odometerMiles; }
    public String getShiftState() { return shiftState; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getSpeedMph() { return speedMph; }
    public String getDestinationName() { return destinationName; }
    public Double getDestinationLatitude() { return destinationLatitude; }
    public Double getDestinationLongitude() { return destinationLongitude; }
}
