package com.zenzmoney.common.domain;

import com.zenzmoney.common.util.IdGenerator;
import com.zenzmoney.common.util.SnowflakeIdGenerator;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityIdGenerator implements IdentifierGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EntityIdGenerator.class);

    // Hibernate instantiates this generator by reflection, so the shared IdGenerator
    // cannot be injected — it is handed over once at startup instead.
    private static volatile IdGenerator delegate;

    public static void configure(IdGenerator idGenerator) {
        delegate = idGenerator;
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object entity) {
        if (entity instanceof BaseEntity baseEntity && baseEntity.getId() != null) {
            return baseEntity.getId();
        }
        return delegate().generateId();
    }

    private static IdGenerator delegate() {
        IdGenerator current = delegate;
        if (current == null) {
            synchronized (EntityIdGenerator.class) {
                current = delegate;
                if (current == null) {
                    logger.warn("No IdGenerator configured — falling back to datacenter 0 / worker 0; "
                            + "ids will collide if another instance shares those coordinates");
                    current = new SnowflakeIdGenerator(0, 0);
                    delegate = current;
                }
            }
        }
        return current;
    }
}
