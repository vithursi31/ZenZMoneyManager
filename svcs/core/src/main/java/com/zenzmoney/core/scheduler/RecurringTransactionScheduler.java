package com.zenzmoney.core.scheduler;

import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.service.RecurringTransactionService;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives recurring-transaction generation (§1.8). On each tick it fetches the due
 * templates and processes each in its own transaction via
 * {@link RecurringTransactionService#runTemplate(String)} — a call across bean
 * boundaries so the per-template {@code @Transactional} actually applies, and so one
 * failing template (e.g. its category was deleted) is isolated and the rest still run.
 *
 * <p>Catch-up lives in {@code runTemplate}, so the tick cadence only bounds latency,
 * not correctness: missed occurrences during downtime are generated on the next tick.
 * The tick is what bounds the gap between an occurrence leaving the upcoming-payments
 * window (F-1.7) and its row existing, so it runs every 15 minutes rather than hourly.
 * Override with {@code zenzmoney.recurring.cron}.
 */
@Component
public class RecurringTransactionScheduler {

    /** Routed to scheduler.log — a background pass is read on its own, not amid request traffic. */
    private static final Logger log = AppLog.SCHEDULER;

    private final RecurringTransactionService recurringService;

    public RecurringTransactionScheduler(RecurringTransactionService recurringService) {
        this.recurringService = recurringService;
    }

    @Scheduled(cron = "${zenzmoney.recurring.cron:0 0/15 * * * *}")
    public void generateDue() {
        List<String> due = recurringService.dueTemplateIds();
        if (due.isEmpty()) {
            return;
        }
        int templates = 0;
        int generated = 0;
        for (String id : due) {
            try {
                int n = recurringService.runTemplate(id);
                if (n > 0) {
                    templates++;
                    generated += n;
                }
            } catch (Exception e) {
                log.warn("Recurring template {} failed to generate: {}", id, e.toString());
            }
        }
        log.info("Recurring generation: {} transactions from {} of {} due template(s)",
                generated, templates, due.size());
    }
}
