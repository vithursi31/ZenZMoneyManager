package com.zenzmoney.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Id generator from Twitter's Snowflake — 41-bit timestamp, 5-bit datacenter,
 * 5-bit worker, 12-bit per-millisecond sequence, rendered as a decimal string.
 */
public class SnowflakeIdGenerator implements IdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    private static final long EPOCH = 1288834974657L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    public static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    public static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private final long datacenterId;
    private final long workerId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private long lastId = -1L;

    public SnowflakeIdGenerator(final long datacenterId, final long workerId) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId must be between 0 and " + MAX_DATACENTER_ID);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    @Override
    public String generateId() {
        return String.valueOf(nextId());
    }

    synchronized long nextId() {
        long timestamp = timestamp();

        if (timestamp < lastTimestamp) {
            logger.error("Clock moved backwards by {}ms — refusing to generate an id", lastTimestamp - timestamp);
            throw new IllegalStateException("Clock moved backwards; refusing to generate an id for "
                    + (lastTimestamp - timestamp) + " milliseconds");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;

        long id = ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;

        if (id <= lastId) {
            throw new IllegalStateException("Generated id moved backwards: " + id + " <= " + lastId);
        }
        lastId = id;

        return id;
    }

    private long tilNextMillis(final long lastTimestamp) {
        long timestamp = timestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = timestamp();
        }
        return timestamp;
    }

    protected long timestamp() {
        return System.currentTimeMillis();
    }
}
