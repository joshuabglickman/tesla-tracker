package com.nextrayvision.teslatracking.tracking;

import com.nextrayvision.teslatracking.model.OdometerReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MileageCalculatorTest {

    private static OdometerReading at(String instant, double miles) {
        return new OdometerReading("VIN", Instant.parse(instant), miles, null, null, null, null);
    }

    @Test
    void creditsDistanceToTheDayOfTheLaterReading() {
        Map<LocalDate, Double> days = MileageCalculator.milesPerDay(List.of(
                at("2026-10-01T08:00:00Z", 1000.0),
                at("2026-10-01T09:00:00Z", 1012.5),
                at("2026-10-01T18:00:00Z", 1025.0),
                at("2026-10-03T07:30:00Z", 1100.0)), ZoneOffset.UTC);

        assertThat(days).containsOnlyKeys(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        assertThat(days.get(LocalDate.of(2026, 10, 1))).isCloseTo(25.0, within(1e-9));
        assertThat(days.get(LocalDate.of(2026, 10, 3))).isCloseTo(75.0, within(1e-9));
        assertThat(MileageCalculator.milesBetween(days, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)))
                .isCloseTo(75.0, within(1e-9));
    }

    @Test
    void ignoresOdometerGoingBackwards() {
        Map<LocalDate, Double> days = MileageCalculator.milesPerDay(List.of(
                at("2026-10-01T08:00:00Z", 1000.0),
                at("2026-10-01T09:00:00Z", 999.0)), ZoneOffset.UTC);

        assertThat(days).isEmpty();
    }
}
