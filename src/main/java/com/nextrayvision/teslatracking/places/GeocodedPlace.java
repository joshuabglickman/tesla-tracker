package com.nextrayvision.teslatracking.places;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/** Cached reverse-geocoding result for a coordinate rounded to about 10 m (see {@link PlaceService#key}). */
@Entity
public class GeocodedPlace {

    @Id
    private String coordKey;

    /** Point of interest or building name, e.g. "Stanford Shopping Center". Null for a plain street address. */
    private String name;

    @Column(length = 1000)
    private String address;

    private Instant resolvedAt;

    protected GeocodedPlace() {
    }

    public GeocodedPlace(String coordKey, String name, String address, Instant resolvedAt) {
        this.coordKey = coordKey;
        this.name = name;
        this.address = address;
        this.resolvedAt = resolvedAt;
    }

    public String getCoordKey() { return coordKey; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public Instant getResolvedAt() { return resolvedAt; }
}
