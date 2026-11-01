package com.nextrayvision.teslatracking.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeocodingServiceTest {

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void formatsShortAddressAndKeepsPointOfInterestName() throws Exception {
        JsonNode result = json("""
                {"name": "Stanford Shopping Center",
                 "display_name": "Stanford Shopping Center, 660, Stanford Shopping Center, Palo Alto, ...",
                 "address": {"house_number": "660", "road": "Stanford Shopping Center", "city": "Palo Alto",
                             "state": "California", "postcode": "94304", "country": "United States"}}""");

        assertThat(GeocodingService.formatAddress(result))
                .isEqualTo("660 Stanford Shopping Center, Palo Alto, California 94304");
        // The name matches the road here, so it adds nothing beyond the address.
        assertThat(GeocodingService.placeName(result)).isNull();

        JsonNode poi = json("""
                {"name": "Tesla Supercharger", "address": {"road": "El Camino Real", "town": "Menlo Park"}}""");
        assertThat(GeocodingService.placeName(poi)).isEqualTo("Tesla Supercharger");
        assertThat(GeocodingService.formatAddress(poi)).isEqualTo("El Camino Real, Menlo Park");
    }

    @Test
    void fallsBackToDisplayNameWhenAddressHasNoUsefulParts() throws Exception {
        JsonNode result = json("""
                {"name": "", "display_name": "Somewhere remote", "address": {"country": "Iceland"}}""");

        assertThat(GeocodingService.formatAddress(result)).isEqualTo("Somewhere remote");
        assertThat(GeocodingService.placeName(result)).isNull();
    }

    @Test
    void measuresDistanceAndRoundsKeys() {
        // One thousandth of a degree of latitude is about 111 m.
        assertThat(PlaceService.distanceMeters(37.0, -122.0, 37.001, -122.0)).isCloseTo(111.2, within(0.5));
        assertThat(PlaceService.key(37.441912, -122.143049)).isEqualTo("37.4419,-122.1430");
    }
}
