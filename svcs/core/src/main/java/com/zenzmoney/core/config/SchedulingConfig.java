package com.zenzmoney.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support, which drives recurring-transaction
 * generation ({@link com.zenzmoney.core.scheduler.RecurringTransactionScheduler}).
 * The deployment is a single always-on instance (see DEPLOYMENT.md), so plain
 * scheduling is safe; a multi-instance deployment would need a distributed lock
 * (e.g. ShedLock) so only one node generates per tick.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
