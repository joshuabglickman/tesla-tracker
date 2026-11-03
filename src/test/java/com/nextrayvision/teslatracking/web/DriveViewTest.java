package com.nextrayvision.teslatracking.web;

import com.nextrayvision.teslatracking.places.PlaceService.PlaceLabel;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Destination;
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
