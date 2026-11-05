package com.nextrayvision.teslatracking.web;

import com.nextrayvision.teslatracking.places.PlaceService.PlaceLabel;
import com.nextrayvision.teslatracking.places.PlaceService;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Destination;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Drive;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.nextrayvision.teslatracking.web.ApiController.DriveView.arrival;
import static org.assertj.core.api.Assertions.assertThat;

class DriveViewTest {

    private static final PlaceLabel CHARGER = new PlaceLabel("Volta Charging", "Hickory Ave, Baltimore, MD 21211", null);

    @Test
    void driveThatStopsAtTheNavigationDestinationIsNamedAfterIt() {
        Destination full = new Destination("Giant, 1020 W 41st St, Baltimore, MD 21211", 39.3386, -76.6355);
        assertThat(arrival(CHARGER, full, 39.338366, -76.63552))
                .isEqualTo(new PlaceLabel("Giant", "1020 W 41st St, Baltimore, MD 21211", null));

        Destination nameOnly = new Destination("Giant", 39.3386, -76.6355);
        assertThat(arrival(CHARGER, nameOnly, 39.338366, -76.63552))
                .isEqualTo(new PlaceLabel("Giant", "Hickory Ave, Baltimore, MD 21211", null));
    }

    @Test
    void combinesNameAndAddressForTheCsv() {
        assertThat(ApiController.fullPlace(new PlaceLabel("Giant", "1020 W 41st St, Baltimore, MD 21211", null)))
                .isEqualTo("Giant, 1020 W 41st St, Baltimore, MD 21211");
        assertThat(ApiController.fullPlace(new PlaceLabel(null, "Hickory Ave, Baltimore, MD 21211", null)))
                .isEqualTo("Hickory Ave, Baltimore, MD 21211");
        assertThat(ApiController.fullPlace(null)).isEmpty();
    }

    @Test
    void approximateDriveIsDatedAtTheMiddleOfItsRange() {
        Instant start = Instant.parse("2026-11-03T13:16:00Z");
        Instant end = Instant.parse("2026-11-03T14:08:00Z");
        assertThat(view(start, end, true).when()).isEqualTo(Instant.parse("2026-11-03T13:42:00Z"));
        assertThat(view(start, end, false).when()).isEqualTo(start);
        assertThat(view(start, null, false).when()).isEqualTo(start);
    }

    @Test
    void tripBackStartsFromThePlaceTheLastDriveArrivedAt() {
        PlaceLabel marina = new PlaceLabel("Anchorage Marina", "2339 Boston St, Baltimore, MD 21231", null);
        PlaceLabel park = new PlaceLabel("Anchorage Promenade Park", "2339 Boston St, Baltimore, MD 21231", null);
        PlaceLabel home = new PlaceLabel("Home", "725 Newington Ave, Baltimore, MD 21217", 1L);
        ApiController.DriveView there = drive(home, 39.313339, -76.630338, marina, 39.28078, -76.581492);
        ApiController.DriveView back = drive(park, 39.28078, -76.581492, home, 39.313339, -76.630338);

        assertThat(back.departingFrom(there).startPlace()).isEqualTo(marina);
        assertThat(there.departingFrom(back).startPlace()).isEqualTo(home);
        ApiController.DriveView elsewhere = drive(park, 39.29, -76.59, home, 39.313339, -76.630338);
        assertThat(elsewhere.departingFrom(there).startPlace()).isEqualTo(park);
    }

    @Test
    void navigatingToYourPlaceByNameUsesItsAddressAnywhereInTheLot() {
        PlaceLabel yours = new PlaceLabel("Anchorage Marina", "2401 Boston St, Baltimore, MD 21224", 2L);
        PlaceService.Labeler labeler = new PlaceService.Labeler() {
            @Override
            public PlaceLabel label(Double latitude, Double longitude) {
                return new PlaceLabel("Anchorage Marina", "2363 Boston St, Baltimore, MD 21224", null);
            }

            @Override
            public Long savedPlaceAt(double latitude, double longitude) {
                return null;
            }

            @Override
            public PlaceLabel savedNamed(String name, double latitude, double longitude) {
                return name.equals("Anchorage Marina") ? yours : null;
            }
        };
        Instant start = Instant.parse("2026-11-04T09:46:00Z");
        Drive drive = new Drive(start, start.plusSeconds(1800), false, 4.5, 39.313294, -76.630342, 39.282335, -76.583337,
                new Destination("Anchorage Marina", 39.280643, -76.581671), List.of());

        assertThat(ApiController.DriveView.of(drive, labeler).endPlace()).isEqualTo(yours);
    }

    private static ApiController.DriveView drive(PlaceLabel from, double fromLat, double fromLon,
                                                 PlaceLabel to, double toLat, double toLon) {
        Instant start = Instant.parse("2026-11-03T21:42:00Z");
        return new ApiController.DriveView(start, start.plusSeconds(1800), false, 4.5,
                fromLat, fromLon, from, toLat, toLon, to, null, List.of());
    }

    private static ApiController.DriveView view(Instant start, Instant end, boolean approximate) {
        return new ApiController.DriveView(start, end, approximate, 1.0, null, null, null, null, null, null, null, List.of());
    }

    @Test
    void keepsTheLookedUpPlaceWhenTheCarStoppedElsewhereOrTheSpotIsYours() {
        Destination elsewhere = new Destination("Giant", 39.40, -76.60);
        assertThat(arrival(CHARGER, elsewhere, 39.338366, -76.63552)).isEqualTo(CHARGER);

        PlaceLabel mine = new PlaceLabel("Groceries", "Hickory Ave", 7L);
        assertThat(arrival(mine, new Destination("Giant", 39.3386, -76.6355), 39.338366, -76.63552)).isEqualTo(mine);
    }
}
