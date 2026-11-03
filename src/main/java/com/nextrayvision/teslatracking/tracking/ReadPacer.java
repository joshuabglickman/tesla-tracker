package com.nextrayvision.teslatracking.tracking;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides when to read one awake car. It is read on every poll, so a drive is noticed within one poll
 * interval, except that once it has sat parked and unchanged for a while, reads pause so the car can fall
 * asleep (reading it keeps it awake). If it is still awake when the pause ends, something else is keeping
 * it up, so reads resume and the cycle repeats.
 */
final class ReadPacer {

    private final Duration idleBeforePause;
    private final Duration pause;

    private Instant idleSince;
    private Instant pausedUntil;

    ReadPacer(Duration idleBeforePause, Duration pause) {
        this.idleBeforePause = idleBeforePause;
        this.pause = pause;
    }

    /**
     * @param justWoke the car was asleep or offline on the previous poll
     * @param wasDriving the last read found the car in gear
     */
    boolean shouldRead(Instant now, boolean justWoke, boolean wasDriving) {
        if (justWoke || wasDriving) {
            pausedUntil = null;
            return true;
        }
        if (pausedUntil != null) {
            if (now.isBefore(pausedUntil)) {
                return false;
            }
            pausedUntil = null;
            idleSince = null;
        }
        return true;
    }

    /** @param moved the odometer changed since the previous read */
    void afterRead(Instant now, boolean driving, boolean moved) {
        if (driving || moved) {
            idleSince = null;
        } else if (idleSince == null) {
            idleSince = now;
        } else if (!now.isBefore(idleSince.plus(idleBeforePause))) {
            pausedUntil = now.plus(pause);
            idleSince = null;
        }
    }
}
