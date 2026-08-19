package com.zenzmoney.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    /** Drives the clock by hand so sequence rollover and clock drift are testable. */
    private static class FixedClockGenerator extends SnowflakeIdGenerator {
        private long now = 1_700_000_000_000L;

        FixedClockGenerator(long datacenterId, long workerId) {
            super(datacenterId, workerId);
        }

        @Override
        protected long timestamp() {
            return now;
        }

        void setNow(long now) {
            this.now = now;
        }
    }

    @Test
    void generatesNumericId() {
        IdGenerator generator = new SnowflakeIdGenerator(1, 1);

        String id = generator.generateId();

        assertTrue(id.matches("\\d+"), "expected digits only, got: " + id);
    }

    @Test
    void generatesStrictlyIncreasingIds() {
        IdGenerator generator = new SnowflakeIdGenerator(1, 1);

        long previous = Long.parseLong(generator.generateId());
        for (int i = 0; i < 1_000; i++) {
            long current = Long.parseLong(generator.generateId());
            assertTrue(current > previous, "ids must increase: " + current + " <= " + previous);
            previous = current;
        }
    }

    @Test
    void generatesNoDuplicates() {
        IdGenerator generator = new SnowflakeIdGenerator(1, 1);

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.generateId());
        }

        assertEquals(10_000, ids.size());
    }

    @Test
    void differentWorkersNeverCollideWithinSameMillisecond() {
        FixedClockGenerator workerOne = new FixedClockGenerator(1, 1);
        FixedClockGenerator workerTwo = new FixedClockGenerator(1, 2);

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(workerOne.generateId());
            ids.add(workerTwo.generateId());
        }

        assertEquals(200, ids.size());
    }

    @Test
    void waitsForNextMillisecondWhenSequenceOverflows() {
        FixedClockGenerator generator = new FixedClockGenerator(1, 1) {
            private int calls = 0;

            @Override
            protected long timestamp() {
                // The 4097th id in one millisecond exhausts the 12-bit sequence; advance
                // the clock so tilNextMillis() can terminate rather than spinning forever.
                if (++calls > 4_098) {
                    setNow(1_700_000_000_001L);
                }
                return super.timestamp();
            }
        };

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 4_100; i++) {
            ids.add(generator.generateId());
        }

        assertEquals(4_100, ids.size());
    }

    @Test
    void rejectsIdWhenClockMovesBackwards() {
        FixedClockGenerator generator = new FixedClockGenerator(1, 1);
        generator.generateId();

        generator.setNow(1_699_999_999_000L);

        assertThrows(IllegalStateException.class, generator::generateId);
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1, -1));
    }
}
