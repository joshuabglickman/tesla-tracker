package com.nextrayvision.teslatracking.places;

import com.nextrayvision.teslatracking.model.OdometerReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HomeDetectorTest {

    private static OdometerReading at(String dateTime, double miles, String gear, double lat, double lon) {
        return new OdometerReading("VIN", Instant.parse("2026-10-" + dateTime + ":00Z"), miles, gear, lat, lon, null);
    }

    /** Parks at home each evening, and is seen driving to work each morning. */
    private static List<OdometerReading> days(int count) {
        List<OdometerReading> r = new ArrayList<>();
        double miles = 1000;
        for (int d = 1; d <= count; d++) {
            String day = String.format("%02d", d);
            r.add(at(day + "T07:50", miles += 2, "D", 39.32, -76.62)); // leaving home
            r.add(at(day + "T08:00", miles += 8, "P", 40.0, -75.0)); // at work
            r.add(at(day + "T18:00", miles += 10, "P", 39.3133, -76.6303)); // home for the night
        }
        return r;
    }

    @Test
    void findsWhereTheCarSpendsMostNights() {
        var spot = HomeDetector.overnightSpot(days(5), ZoneOffset.UTC);

        assertThat(spot).isPresent();
        assertThat(spot.get().lat()).isCloseTo(39.3133, within(1e-6));
        assertThat(spot.get().nights()).isEqualTo(4);
    }

    @Test
    void needsSeveralNightsBeforeGuessing() {
        assertThat(HomeDetector.overnightSpot(days(2), ZoneOffset.UTC)).isEmpty();
        assertThat(HomeDetector.overnightSpot(List.of(), ZoneOffset.UTC)).isEmpty();
    }

    @Test
    void skipsNightsWhenTheCarMovedUnseen() {
        // The morning drives weren't seen: the car next shows up parked elsewhere with miles added,
        // so it may have left before 3 a.m. and no night is certain.
        List<OdometerReading> unseen = days(5).stream().filter(x -> !x.isDriving()).toList();
        assertThat(HomeDetector.overnightSpot(unseen, ZoneOffset.UTC)).isEmpty();
    }
}
