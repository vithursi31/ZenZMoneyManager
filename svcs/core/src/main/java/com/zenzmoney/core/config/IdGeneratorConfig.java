package com.zenzmoney.core.config;

import com.zenzmoney.common.domain.EntityIdGenerator;
import com.zenzmoney.common.util.IdGenerator;
import com.zenzmoney.common.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

    private static final Logger logger = LoggerFactory.getLogger(IdGeneratorConfig.class);

    private final long datacenterId;
    private final long workerId;

    public IdGeneratorConfig(@Value("${zenzmoney.id.datacenter-id}") long datacenterId,
                             @Value("${zenzmoney.id.worker-id}") long workerId) {
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    @Bean
    public IdGenerator idGenerator() {
        IdGenerator idGenerator = new SnowflakeIdGenerator(datacenterId, workerId);
        EntityIdGenerator.configure(idGenerator);
        logger.info("Snowflake id generator initialised: datacenter={} worker={}", datacenterId, workerId);
        return idGenerator;
    }
}
