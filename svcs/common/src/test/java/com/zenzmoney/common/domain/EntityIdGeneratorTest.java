package com.zenzmoney.common.domain;

import com.zenzmoney.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityIdGeneratorTest {

    private static class TestEntity extends BaseEntity {
    }

    private final EntityIdGenerator generator = new EntityIdGenerator();

    @BeforeEach
    void setUp() {
        EntityIdGenerator.configure(new SnowflakeIdGenerator(1, 1));
    }

    @Test
    void generatesNumericIdWhenEntityHasNoId() {
        TestEntity entity = new TestEntity();

        Object id = generator.generate(null, entity);

        assertNotNull(id);
        assertTrue(((String) id).matches("\\d+"), "expected digits only, got: " + id);
    }

    @Test
    void preservesAlreadyAssignedId() {
        TestEntity entity = new TestEntity();
        entity.setId("pre-assigned-id");

        Object id = generator.generate(null, entity);

        assertEquals("pre-assigned-id", id);
    }

    @Test
    void generatesNumericIdForNonBaseEntityObjects() {
        Object id = generator.generate(null, new Object());

        assertNotNull(id);
        assertTrue(((String) id).matches("\\d+"), "expected digits only, got: " + id);
    }

    @Test
    void usesTheConfiguredGenerator() {
        EntityIdGenerator.configure(() -> "stub-id");

        Object id = generator.generate(null, new TestEntity());

        assertEquals("stub-id", id);
    }
}
