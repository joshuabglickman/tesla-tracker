package com.nextrayvision.teslatracking.tracking;

import com.nextrayvision.teslatracking.model.OdometerReading;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits readings into drives. A drive's start location and odometer come from the last parked
 * reading before the car was seen in gear, its start time from the first reading in gear, and its
 * end from the first parked reading after. Two parked readings with miles between them also count
 * as a drive (one too short to be seen in gear, or made while this app was down); its times are
 * only known to be somewhere between those two readings, so it is flagged approximate. A drive's
 * navigation destination is the last one the car was routing to while in gear.
 */
public final class DriveDetector {

    private static final double MIN_DRIVE_MILES = 0.1;

    private DriveDetector() {
    }

    public record Point(Instant at, double lat, double lon) {
    }

    /** Where the car's navigation was routing to; any field may be null. */
    public record Destination(String name, Double lat, Double lon) {
    }

    /**
     * @param endedAt null while the drive is still in progress
     * @param approximate the drive happened at an unknown time between startedAt and endedAt
     * @param destination null when no navigation route was seen during the drive
     */
    public record Drive(Instant startedAt, Instant endedAt, boolean approximate, double miles,
                        Double startLat, Double startLon, Double endLat, Double endLon,
                        Destination destination, List<Point> path) {
    }

    /** @param readings one vehicle's readings in ascending time order */
    public static List<Drive> detect(List<OdometerReading> readings) {
        List<Drive> drives = new ArrayList<>();
        OdometerReading lastParked = null;
        List<OdometerReading> open = null;

        for (OdometerReading r : readings) {
            if (r.isDriving()) {
                if (open == null) {
                    open = new ArrayList<>();
                    if (lastParked != null) {
                        open.add(lastParked);
                    }
                }
                open.add(r);
                continue;
            }
            if (open != null) {
                open.add(r);
                drives.add(drive(open, false, false));
                open = null;
            } else if (lastParked != null && r.getOdometerMiles() - lastParked.getOdometerMiles() >= MIN_DRIVE_MILES) {
                drives.add(drive(List.of(lastParked, r), false, true));
            }
            lastParked = r;
        }
        if (open != null) {
            drives.add(drive(open, true, false));
        }
        return drives;
    }

    private static Drive drive(List<OdometerReading> points, boolean inProgress, boolean approximate) {
        OdometerReading first = points.getFirst();
        OdometerReading last = points.getLast();
        Instant startedAt = approximate ? first.getRecordedAt()
                : points.stream().filter(OdometerReading::isDriving).findFirst().orElse(first).getRecordedAt();
        List<Point> path = points.stream()
                .filter(OdometerReading::hasLocation)
                .map(p -> new Point(p.getRecordedAt(), p.getLatitude(), p.getLongitude()))
                .toList();
        Destination destination = points.stream()
                .filter(p -> p.isDriving() && p.hasDestination())
                .reduce((a, b) -> b)
                .map(p -> new Destination(p.getDestinationName(), p.getDestinationLatitude(), p.getDestinationLongitude()))
                .orElse(null);
        return new Drive(startedAt, inProgress ? null : last.getRecordedAt(), approximate,
                Math.max(0, last.getOdometerMiles() - first.getOdometerMiles()),
                first.getLatitude(), first.getLongitude(),
                inProgress ? null : last.getLatitude(), inProgress ? null : last.getLongitude(),
                destination, path);
    }
}
