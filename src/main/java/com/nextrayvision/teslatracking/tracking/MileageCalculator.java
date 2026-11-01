package com.nextrayvision.teslatracking.tracking;

import com.nextrayvision.teslatracking.model.OdometerReading;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns odometer readings into miles per day. The distance between two consecutive
 * readings is credited to the day of the later reading.
 */
public final class MileageCalculator {

    private MileageCalculator() {
    }

    /** @param readings in ascending time order */
    public static TreeMap<LocalDate, Double> milesPerDay(List<OdometerReading> readings, ZoneId zone) {
        TreeMap<LocalDate, Double> days = new TreeMap<>();
        for (int i = 1; i < readings.size(); i++) {
            double delta = readings.get(i).getOdometerMiles() - readings.get(i - 1).getOdometerMiles();
            if (delta > 0) {
                LocalDate day = readings.get(i).getRecordedAt().atZone(zone).toLocalDate();
                days.merge(day, delta, Double::sum);
            }
        }
        return days;
    }

    public static double milesBetween(Map<LocalDate, Double> days, LocalDate fromInclusive, LocalDate toInclusive) {
        return days.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(fromInclusive) && !e.getKey().isAfter(toInclusive))
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }
}
