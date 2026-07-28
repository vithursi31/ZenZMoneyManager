package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Payee;
import com.zenzmoney.core.repository.PayeeRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreatePayeeRequest;
import com.zenzmoney.core.web.dto.PayeeResponse;
import com.zenzmoney.core.web.dto.UpdatePayeeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Manages {@link Payee} entities (§1.5b), scoped to the authenticated user. The
 * central operation is {@link #resolveOrCreate(String, String)} — every
 * transaction-writing path turns a typed payee name into a deduped payee row
 * through it, so payees are never stored as opaque strings.
 */
@Service
public class PayeeService {

    /** Mutations only. Reads are already covered by the per-request line MdcContextFilter writes. */
    private static final Logger log = LoggerFactory.getLogger(PayeeService.class);

    private final PayeeRepository payeeRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringTransactionRepository recurringRepository;
    private final CurrentUserService currentUser;

    public PayeeService(PayeeRepository payeeRepository,
                        TransactionRepository transactionRepository,
                        RecurringTransactionRepository recurringRepository,
                        CurrentUserService currentUser) {
        this.payeeRepository = payeeRepository;
        this.transactionRepository = transactionRepository;
        this.recurringRepository = recurringRepository;
        this.currentUser = currentUser;
    }

    /**
     * Resolves a typed payee name to a payee id for the given user, creating the
     * row on first use. Deduped by normalized name so "Keells" and "keells"
     * collapse to one. Returns {@code null} for a blank name (no payee).
     * Intended to be called from transaction-writing services within their own
     * transaction.
     */
    @Transactional
    public String resolveOrCreate(String userId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();
        String normalized = normalize(name);
        return payeeRepository.findByUserIdAndNormalizedName(userId, normalized)
                .map(Payee::getId)
                .orElseGet(() -> {
                    Payee p = new Payee();
                    p.setUserId(userId);
                    p.setName(name);
                    p.setNormalizedName(normalized);
                    String id = payeeRepository.save(p).getId();
                    // DEBUG, not INFO: this fires as a side effect of transaction entry, so at INFO
                    // it would double every ledger write with a line nobody asked about.
                    log.debug("Payee auto-created on first use: {} (payee {}, user {})", name, id, userId);
                    return id;
                });
    }

    @Transactional
    public PayeeResponse create(CreatePayeeRequest req) {
        String userId = currentUser.requireUserId();
        String name = req.getName().trim();
        String normalized = normalize(name);
        // Reuse the existing row if the name already resolves (idempotent create).
        Payee payee = payeeRepository.findByUserIdAndNormalizedName(userId, normalized)
                .orElseGet(Payee::new);
        payee.setUserId(userId);
        payee.setName(name);
        payee.setNormalizedName(normalized);
        if (req.getColor() != null) payee.setColor(req.getColor());
        if (req.getIcon() != null) payee.setIcon(req.getIcon());
        Payee saved = payeeRepository.save(payee);
        log.info("Payee created: {} (payee {}, user {})", saved.getName(), saved.getId(), userId);
        return PayeeResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PayeeResponse> list() {
        String userId = currentUser.requireUserId();
        return payeeRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Payee::getName, String.CASE_INSENSITIVE_ORDER))
                .map(PayeeResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public PayeeResponse get(String id) {
        return PayeeResponse.of(requireOwned(id, currentUser.requireUserId()));
    }

    @Transactional
    public PayeeResponse update(String id, UpdatePayeeRequest req) {
        String userId = currentUser.requireUserId();
        Payee payee = requireOwned(id, userId);
        if (req.getName() != null && !req.getName().isBlank()) {
            String name = req.getName().trim();
            String normalized = normalize(name);
            payeeRepository.findByUserIdAndNormalizedName(userId, normalized)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new BadRequestException("Another payee with that name already exists.");
                    });
            payee.setName(name);
            payee.setNormalizedName(normalized);
        }
        if (req.getColor() != null) payee.setColor(req.getColor());
        if (req.getIcon() != null) payee.setIcon(req.getIcon());
        log.info("Payee updated: {} (payee {}, user {})", payee.getName(), id, userId);
        return PayeeResponse.of(payeeRepository.save(payee));
    }

    /**
     * Deletes a payee only when nothing references it (§1.5b). A referenced payee
     * should be left unused or (Phase 2) merged.
     */
    @Transactional
    public void delete(String id) {
        Payee payee = requireOwned(id, currentUser.requireUserId());
        if (transactionRepository.existsByPayeeId(id) || recurringRepository.existsByPayeeId(id)) {
            throw new BadRequestException("Payee is used by transactions and cannot be deleted.");
        }
        payeeRepository.delete(payee);
        // Hard delete, allowed only because nothing referenced it — this line is the last record.
        log.info("Payee deleted: {} (payee {}, user {})", payee.getName(), id, payee.getUserId());
    }

    private Payee requireOwned(String id, String userId) {
        return payeeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Payee not found"));
    }

    /** Trim, collapse internal whitespace, lower-case — the dedup key. */
    private String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
