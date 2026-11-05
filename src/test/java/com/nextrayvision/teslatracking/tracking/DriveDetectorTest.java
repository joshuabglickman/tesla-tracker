package com.nextrayvision.teslatracking.tracking;

import com.nextrayvision.teslatracking.model.OdometerReading;
import com.nextrayvision.teslatracking.tracking.DriveDetector.Drive;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DriveDetectorTest {

    private static OdometerReading at(String time, double miles, String gear, double lat, double lon) {
        return new OdometerReading("VIN", Instant.parse("2026-10-01T" + time + ":00Z"), miles, gear, lat, lon, null);
    }

    @Test
    void driveRunsFromLastParkedToFirstParkedReading() {
        List<Drive> drives = DriveDetector.detect(List.of(
                at("08:00", 1000.0, "P", 1, 1),
                at("08:05", 1003.0, "D", 2, 2),
                at("08:10", 1008.0, "D", 3, 3),
                at("08:15", 1010.0, "P", 4, 4)));

        assertThat(drives).hasSize(1);
        Drive d = drives.getFirst();
        assertThat(d.miles()).isCloseTo(10.0, within(1e-9));
        // The car may have sat parked for hours, so the start time is the first reading in gear.
        assertThat(d.startedAt()).isEqualTo(Instant.parse("2026-10-01T08:05:00Z"));
        assertThat(d.endedAt()).isEqualTo(Instant.parse("2026-10-01T08:15:00Z"));
        assertThat(d.approximate()).isFalse();
        assertThat(d.startLat()).isEqualTo(1);
        assertThat(d.endLat()).isEqualTo(4);
        assertThat(d.path()).hasSize(4);
    }

    @Test
    void milesBetweenTwoParkedReadingsCountAsADrive() {
        List<Drive> drives = DriveDetector.detect(List.of(
                at("08:00", 1000.0, null, 1, 1),
                at("12:00", 1004.0, "P", 2, 2)));

        assertThat(drives).hasSize(1);
        assertThat(drives.getFirst().miles()).isCloseTo(4.0, within(1e-9));
        assertThat(drives.getFirst().approximate()).isTrue();
    }

    @Test
    void driveKeepsTheLastNavigationDestinationSetWhileDriving() {
        List<Drive> drives = DriveDetector.detect(List.of(
                at("08:00", 1000.0, "P", 1, 1).withDestination("Old plan", 9.0, 9.0),
                at("08:05", 1003.0, "D", 2, 2).withDestination("Costco", 5.0, 5.0),
                at("08:10", 1008.0, "D", 3, 3).withDestination("Tesla Supercharger", 4.0, 4.0),
                at("08:15", 1010.0, "P", 4, 4)));

        assertThat(drives.getFirst().destination().name()).isEqualTo("Tesla Supercharger");
        assertThat(drives.getFirst().destination().lat()).isEqualTo(4.0);

        List<Drive> withoutRoute = DriveDetector.detect(List.of(
                at("08:00", 1000.0, "P", 1, 1),
                at("08:05", 1003.0, "D", 2, 2),
                at("08:15", 1010.0, "P", 4, 4)));
        assertThat(withoutRoute.getFirst().destination()).isNull();
    }

    @Test
    void quickStopBackAtYourPlaceSplitsTheDrive() {
        // Home to the marina, back home for the keys without being seen in Park, then to the marina again.
        List<OdometerReading> readings = List.of(
                at("09:00", 1000.0, "P", 0, 0),
                at("09:20", 1007.0, "D", 5, 5),
                at("09:25", 1010.0, "D", 0, 0),
                at("09:30", 1011.0, "D", 1, 1),
                at("09:45", 1015.0, "P", 9, 9));
        List<Drive> drives = DriveDetector.detect(readings, r -> r.getLatitude() == 0 ? "Home" : null);

        assertThat(drives).hasSize(2);
        assertThat(drives.get(0).miles()).isCloseTo(10.0, within(1e-9));
        assertThat(drives.get(0).endLat()).isEqualTo(0);
        assertThat(drives.get(1).miles()).isCloseTo(5.0, within(1e-9));
        assertThat(drives.get(1).startLat()).isEqualTo(0);
        assertThat(drives.get(1).startedAt()).isEqualTo(Instant.parse("2026-10-01T09:25:00Z"));

        // Leaving home, still in gear inside it, is not a stop.
        List<Drive> leaving = DriveDetector.detect(List.of(
                at("09:00", 1000.0, "P", 0, 0),
                at("09:05", 1000.1, "D", 0, 0),
                at("09:10", 1003.0, "D", 5, 5),
                at("09:15", 1005.0, "P", 9, 9)), r -> r.getLatitude() == 0 ? "Home" : null);
        assertThat(leaving).hasSize(1);
    }

    @Test
    void driveStillInGearIsInProgress() {
        List<Drive> drives = DriveDetector.detect(List.of(
                at("08:00", 1000.0, "P", 1, 1),
                at("08:05", 1002.0, "D", 2, 2)));

        assertThat(drives).hasSize(1);
        assertThat(drives.getFirst().endedAt()).isNull();
        assertThat(drives.getFirst().miles()).isCloseTo(2.0, within(1e-9));
    }
}
