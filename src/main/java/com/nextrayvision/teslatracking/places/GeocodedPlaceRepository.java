package com.nextrayvision.teslatracking.places;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GeocodedPlaceRepository extends JpaRepository<GeocodedPlace, String> {
}
