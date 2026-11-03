package com.nextrayvision.teslatracking.tracking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadPacerTest {

    private static final Instant T0 = Instant.parse("2026-11-03T12:00:00Z");

    private static Instant minute(int m) {
        return T0.plus(Duration.ofMinutes(m));
    }

    /** Polls every 5 minutes for the given span with the car parked and awake; returns the minutes read. */
    private static List<Integer> parkedReads(ReadPacer pacer, int from, int to) {
        List<Integer> read = new ArrayList<>();
        for (int m = from; m <= to; m += 5) {
            if (pacer.shouldRead(minute(m), false, false)) {
                pacer.afterRead(minute(m), false, false);
                read.add(m);
            }
        }
        return read;
    }

    @Test
    void readsEveryPollThenPausesSoTheCarCanSleep() {
        ReadPacer pacer = new ReadPacer(Duration.ofMinutes(15), Duration.ofMinutes(21));

        // Parked and unchanged for 15 minutes, then a 21-minute pause, then the cycle repeats.
        assertThat(parkedReads(pacer, 0, 95)).containsExactly(0, 5, 10, 15, 40, 45, 50, 55, 80, 85, 90, 95);
    }

    @Test
    void wakingUpOrDrivingEndsThePause() {
        ReadPacer pacer = new ReadPacer(Duration.ofMinutes(15), Duration.ofMinutes(21));
        parkedReads(pacer, 0, 15);
        assertThat(pacer.shouldRead(minute(20), false, false)).isFalse();

        assertThat(pacer.shouldRead(minute(25), true, false)).isTrue();
        pacer.afterRead(minute(25), true, true);
        // While driving, every poll reads.
        assertThat(drivingReads(pacer, 30, 60)).isEqualTo(7);
    }

    private static int drivingReads(ReadPacer pacer, int from, int to) {
        int reads = 0;
        for (int m = from; m <= to; m += 5) {
            if (pacer.shouldRead(minute(m), false, true)) {
                pacer.afterRead(minute(m), true, true);
                reads++;
            }
        }
        return reads;
    }
}
