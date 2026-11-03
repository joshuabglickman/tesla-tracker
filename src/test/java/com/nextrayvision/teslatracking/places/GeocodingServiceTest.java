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
    void namesAParkingLotSpotAfterTheNearestBusiness() throws Exception {
        JsonNode charger = json("""
                {"category": "amenity", "type": "charging_station", "name": "Volta Charging",
                 "address": {"road": "Hickory Avenue", "city": "Baltimore", "state": "Maryland",
                             "postcode": "21211", "country_code": "us"}}""");
        JsonNode overpass = json("""
                {"elements": [
                  {"type": "node", "lat": 39.33840, "lon": -76.63550,
                   "tags": {"name": "Volta Charging", "amenity": "charging_station"}},
                  {"type": "node", "lat": 39.33860, "lon": -76.63550,
                   "tags": {"name": "Giant", "shop": "supermarket",
                            "addr:housenumber": "1020", "addr:street": "West 41st Street"}},
                  {"type": "way", "center": {"lat": 39.33900, "lon": -76.63550},
                   "tags": {"name": "Royal Farms", "shop": "convenience"}}]}""");

        assertThat(GeocodingService.isMinorFeature(charger)).isTrue();
        JsonNode giant = GeocodingService.nearestBusiness(overpass, 39.338366, -76.63552).orElseThrow();
        assertThat(giant.path("tags").path("name").asText()).isEqualTo("Giant");
        assertThat(GeocodingService.formatAddress(charger, giant.path("tags")))
                .isEqualTo("1020 W 41st St, Baltimore, MD 21211");
        // Without a street of its own, a business takes the parked spot's address.
        assertThat(GeocodingService.formatAddress(charger, json("{\"name\": \"Royal Farms\"}")))
                .isEqualTo("Hickory Ave, Baltimore, MD 21211");
    }

    @Test
    void abbreviatesUsStreetsButKeepsDirectionsThatAreTheName() {
        assertThat(UsAddress.street("West 41st Street")).isEqualTo("W 41st St");
        assertThat(UsAddress.street("North Avenue")).isEqualTo("North Ave");
        assertThat(UsAddress.street("Main Street Northwest")).isEqualTo("Main St NW");
        assertThat(UsAddress.street("Broadway")).isEqualTo("Broadway");
        assertThat(UsAddress.state("Maryland")).isEqualTo("MD");
    }

    @Test
    void measuresDistanceAndRoundsKeys() {
        // One thousandth of a degree of latitude is about 111 m.
        assertThat(PlaceService.distanceMeters(37.0, -122.0, 37.001, -122.0)).isCloseTo(111.2, within(0.5));
        assertThat(PlaceService.key(37.441912, -122.143049)).isEqualTo("37.4419,-122.1430");
    }
}
