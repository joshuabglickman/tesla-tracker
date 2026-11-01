package com.nextrayvision.teslatracking.places;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A place you named yourself, like "Home" or "Work". Applies to any position within its radius. */
@Entity
public class SavedPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private double latitude;

    private double longitude;

    private double radiusMeters;

    protected SavedPlace() {
    }

    public SavedPlace(String name, double latitude, double longitude, double radiusMeters) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getRadiusMeters() { return radiusMeters; }
}
