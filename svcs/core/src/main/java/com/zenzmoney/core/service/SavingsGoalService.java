package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.GoalStatus;
import com.zenzmoney.common.domain.TimeUtils;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.GoalContribution;
import com.zenzmoney.core.entity.SavingsGoal;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.repository.AccountRepository;
import com.zenzmoney.core.repository.GoalContributionRepository;
import com.zenzmoney.core.repository.SavingsGoalRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.ContributionResponse;
import com.zenzmoney.core.web.dto.CreateContributionRequest;
import com.zenzmoney.core.web.dto.CreateGoalRequest;
import com.zenzmoney.core.web.dto.GoalResponse;
import com.zenzmoney.core.web.dto.UpdateGoalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * CRUD for {@link SavingsGoal} and its {@link GoalContribution} funding events (§1.9).
 * Progress ({@code saved}) is always derived from contribution rows — never stored —
 * so a goal can never diverge from the ledger; {@code status} flips to
 * {@link GoalStatus#ACHIEVED} on the service layer whenever {@code saved ≥ target}.
 * Contributions are reached only through their owning goal, which is how ownership is
 * enforced (the entity carries no {@code userId}).
 */
@Service
public class SavingsGoalService {

    /** Mutations only. Contributions are money movements, so they log their amount. */
    private static final Logger log = LoggerFactory.getLogger(SavingsGoalService.class);

    private final SavingsGoalRepository goalRepository;
    private final GoalContributionRepository contributionRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CurrentUserService currentUser;

    public SavingsGoalService(SavingsGoalRepository goalRepository,
                              GoalContributionRepository contributionRepository,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CurrentUserService currentUser) {
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.currentUser = currentUser;
    }

    // --- goal CRUD ---

    @Transactional
    public GoalResponse create(CreateGoalRequest req) {
        String userId = currentUser.requireUserId();
        Account account = requireActiveAccount(req.getAccountId(), userId);

        SavingsGoal goal = new SavingsGoal();
        goal.setUserId(userId);
        goal.setAccountId(account.getId());
        goal.setName(req.getName().trim());
        goal.setTargetAmount(req.getTargetAmount());
        goal.setCurrency(account.getCurrency());   // follows the backing account (§0.3)
        goal.setTargetDate(req.getTargetDate());
        goal.setStatus(GoalStatus.ACTIVE);
        goal.setColor(req.getColor());
        goal.setIcon(req.getIcon());
        SavingsGoal saved = goalRepository.save(goal);
        log.info("Savings goal created: {} target={} {} on account {} (goal {}, user {})",
                saved.getName(), saved.getTargetAmount(), saved.getCurrency(),
                saved.getAccountId(), saved.getId(), userId);
        return GoalResponse.of(saved, 0);   // no contributions yet
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list(boolean includeArchived) {
        String userId = currentUser.requireUserId();
        return goalRepository.findByUserId(userId).stream()
                .filter(g -> includeArchived || g.getStatus() != GoalStatus.ARCHIVED)
                .map(this::withProgress)
                .toList();
    }

    @Transactional(readOnly = true)
    public GoalResponse get(String id) {
        return withProgress(requireOwned(id, currentUser.requireUserId()));
    }

    @Transactional
    public GoalResponse update(String id, UpdateGoalRequest req) {
        SavingsGoal goal = requireOwned(id, currentUser.requireUserId());
        if (req.getName() != null && !req.getName().isBlank()) {
            goal.setName(req.getName().trim());
        }
        if (req.getTargetAmount() != null) {
            if (req.getTargetAmount() <= 0) {
                throw new BadRequestException("Target amount must be positive.");
            }
            goal.setTargetAmount(req.getTargetAmount());
        }
        if (req.getTargetDate() != null) goal.setTargetDate(req.getTargetDate());
        if (req.getColor() != null) goal.setColor(req.getColor());
        if (req.getIcon() != null) goal.setIcon(req.getIcon());
        goalRepository.save(goal);
        log.info("Savings goal updated: {} target={} {} (goal {}, user {})",
                goal.getName(), goal.getTargetAmount(), goal.getCurrency(), id, goal.getUserId());
        return reevaluateAndRespond(goal);   // a raised/lowered target can change ACHIEVED
    }

    @Transactional
    public GoalResponse archive(String id) {
        SavingsGoal goal = requireOwned(id, currentUser.requireUserId());
        goal.setStatus(GoalStatus.ARCHIVED);
        goalRepository.save(goal);
        log.info("Savings goal archived: {} (goal {}, user {})", goal.getName(), id, goal.getUserId());
        return withProgress(goal);
    }

    /**
     * Deletes a goal only when it has no contributions (§1.9) — a funded goal carries
     * ledger history and should be archived instead. Goals are not soft-deleted; the
     * {@link GoalStatus} enum has no DELETED state.
     */
    @Transactional
    public void delete(String id) {
        SavingsGoal goal = requireOwned(id, currentUser.requireUserId());
        if (!contributionRepository.findByGoalId(id).isEmpty()) {
            throw new BadRequestException("Goal has contributions and cannot be deleted; archive it instead.");
        }
        goalRepository.delete(goal);
        // Hard delete, allowed only because the goal was unfunded — this line is the last record.
        log.info("Savings goal deleted: {} target={} {} (goal {}, user {})",
                goal.getName(), goal.getTargetAmount(), goal.getCurrency(), id, goal.getUserId());
    }

    // --- contributions ---

    @Transactional
    public ContributionResponse addContribution(String goalId, CreateContributionRequest req) {
        String userId = currentUser.requireUserId();
        SavingsGoal goal = requireOwned(goalId, userId);
        if (req.getAmount() <= 0) {
            throw new BadRequestException("Contribution amount must be positive.");
        }

        String transactionId = normalizeId(req.getTransactionId());
        if (transactionId != null) {
            Transaction txn = transactionRepository.findByIdAndUserId(transactionId, userId)
                    .orElseThrow(() -> new NotFoundException("Transaction not found"));
            if (txn.getAmount() != req.getAmount()) {
                throw new BadRequestException("Contribution amount must match the linked transaction.");
            }
            if (!txn.getCurrency().equals(goal.getCurrency())) {
                throw new BadRequestException("Contribution currency must match the goal.");
            }
        }

        GoalContribution c = new GoalContribution();
        c.setGoalId(goalId);
        c.setTransactionId(transactionId);
        c.setAmount(req.getAmount());
        c.setContributedAt(req.getContributedAt() != null && req.getContributedAt() > 0
                ? req.getContributedAt() : TimeUtils.now());
        c.setNote(req.getNote());
        GoalContribution saved = contributionRepository.save(c);

        reevaluateStatus(goal);
        log.info("Goal contribution added: {} {} to goal {} (contribution {}, txn={}, user {})",
                saved.getAmount(), goal.getCurrency(), goalId, saved.getId(),
                transactionId == null ? "none" : transactionId, userId);
        return ContributionResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<ContributionResponse> listContributions(String goalId) {
        requireOwned(goalId, currentUser.requireUserId());   // ownership gate
        return contributionRepository.findByGoalId(goalId).stream()
                .sorted(Comparator.comparingLong(GoalContribution::getContributedAt).reversed())
                .map(ContributionResponse::of)
                .toList();
    }

    @Transactional
    public void deleteContribution(String goalId, String contributionId) {
        SavingsGoal goal = requireOwned(goalId, currentUser.requireUserId());
        GoalContribution c = contributionRepository.findByIdAndGoalId(contributionId, goalId)
                .orElseThrow(() -> new NotFoundException("Contribution not found"));
        contributionRepository.delete(c);
        log.info("Goal contribution deleted: {} {} from goal {} (contribution {}, user {})",
                c.getAmount(), goal.getCurrency(), goalId, contributionId, goal.getUserId());
        reevaluateStatus(goal);   // removing funds can drop a goal back from ACHIEVED
    }

    // --- internals ---

    private GoalResponse withProgress(SavingsGoal goal) {
        return GoalResponse.of(goal, contributionRepository.sumAmountByGoalId(goal.getId()));
    }

    /** Recomputes {@code saved} and flips ACTIVE⇄ACHIEVED accordingly (ARCHIVED is left alone). */
    private long reevaluateStatus(SavingsGoal goal) {
        long saved = contributionRepository.sumAmountByGoalId(goal.getId());
        if (goal.getStatus() == GoalStatus.ACTIVE && saved >= goal.getTargetAmount()) {
            goal.setStatus(GoalStatus.ACHIEVED);
            goalRepository.save(goal);
        } else if (goal.getStatus() == GoalStatus.ACHIEVED && saved < goal.getTargetAmount()) {
            goal.setStatus(GoalStatus.ACTIVE);
            goalRepository.save(goal);
        }
        return saved;
    }

    private GoalResponse reevaluateAndRespond(SavingsGoal goal) {
        long saved = reevaluateStatus(goal);
        return GoalResponse.of(goal, saved);
    }

    private SavingsGoal requireOwned(String id, String userId) {
        return goalRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Goal not found"));
    }

    private Account requireActiveAccount(String id, String userId) {
        Account a = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (a.getStatus() == AccountStatus.DELETED) {
            throw new NotFoundException("Account not found");
        }
        if (a.getStatus() == AccountStatus.ARCHIVED) {
            throw new BadRequestException("Account is archived and cannot back a goal.");
        }
        return a;
    }

    private static String normalizeId(String id) {
        return (id == null || id.isBlank()) ? null : id;
    }
}
