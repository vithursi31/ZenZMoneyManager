package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.GoalStatus;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.GoalContribution;
import com.zenzmoney.core.entity.SavingsGoal;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.GoalContributionRepository;
import com.zenzmoney.core.repository.SavingsGoalRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.ContributionResponse;
import com.zenzmoney.core.web.dto.CreateContributionRequest;
import com.zenzmoney.core.web.dto.CreateGoalRequest;
import com.zenzmoney.core.web.dto.GoalResponse;
import com.zenzmoney.core.web.dto.UpdateGoalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsGoalServiceTest {

    @Mock SavingsGoalRepository goalRepository;
    @Mock GoalContributionRepository contributionRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks SavingsGoalService goalService;

    private User user(String activeCurrency) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency(activeCurrency);
        return u;
    }

    private SavingsGoal goal(String id, String userId, GoalStatus status, long target) {
        SavingsGoal g = new SavingsGoal();
        g.setId(id);
        g.setUserId(userId);
        g.setName("Japan Trip");
        g.setCurrency("USD");
        g.setTargetAmount(target);
        g.setStatus(status);
        return g;
    }

    private Transaction txn(String id, String userId, long amount, String currency) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUserId(userId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(amount);
        t.setCurrency(currency);
        return t;
    }

    private CreateGoalRequest createReq() {
        CreateGoalRequest r = new CreateGoalRequest();
        r.setName("Japan Trip");
        r.setTargetAmount(500_000);
        return r;
    }

    @Test
    void create_usesTheUsersActiveCurrency_andStartsAtZeroSaved() {
        when(currentUser.requireUser()).thenReturn(user("USD"));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GoalResponse resp = goalService.create(createReq());

        ArgumentCaptor<SavingsGoal> saved = ArgumentCaptor.forClass(SavingsGoal.class);
        verify(goalRepository).save(saved.capture());
        assertEquals("USD", saved.getValue().getCurrency());
        assertEquals(GoalStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals(0, resp.getSaved());
        assertEquals(500_000, resp.getRemaining());
    }

    /** A goal is denominated in the active currency, so it cannot precede onboarding. */
    @Test
    void create_rejects_whenNoActiveCurrency() {
        when(currentUser.requireUser()).thenReturn(user(null));

        assertThrows(BadRequestException.class, () -> goalService.create(createReq()));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void create_rejects_nonPositiveTarget() {
        when(currentUser.requireUser()).thenReturn(user("USD"));

        CreateGoalRequest r = createReq();
        r.setTargetAmount(0);
        assertThrows(BadRequestException.class, () -> goalService.create(r));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void addContribution_flipsToAchieved_whenSavedReachesTarget() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contributionRepository.sumAmountByGoalId("g1")).thenReturn(100_000L);

        CreateContributionRequest req = new CreateContributionRequest();
        req.setAmount(100_000);
        ContributionResponse resp = goalService.addContribution("g1", req);

        assertEquals(100_000, resp.getAmount());
        assertEquals(GoalStatus.ACHIEVED, g.getStatus());
        verify(goalRepository).save(g);
    }

    @Test
    void addContribution_staysActive_whenBelowTarget() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contributionRepository.sumAmountByGoalId("g1")).thenReturn(30_000L);

        CreateContributionRequest req = new CreateContributionRequest();
        req.setAmount(30_000);
        goalService.addContribution("g1", req);

        assertEquals(GoalStatus.ACTIVE, g.getStatus());
        verify(goalRepository, never()).save(g);   // no status transition ⇒ no save
    }

    @Test
    void addContribution_withLinkedTransaction_matchingAmountAndCurrency_ok() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(transactionRepository.findByIdAndUserId("t1", "u1"))
                .thenReturn(Optional.of(txn("t1", "u1", 40_000, "USD")));
        when(contributionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contributionRepository.sumAmountByGoalId("g1")).thenReturn(40_000L);

        CreateContributionRequest req = new CreateContributionRequest();
        req.setAmount(40_000);
        req.setTransactionId("t1");
        ContributionResponse resp = goalService.addContribution("g1", req);

        assertEquals("t1", resp.getTransactionId());
    }

    @Test
    void addContribution_rejects_whenLinkedTransactionAmountMismatch() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(transactionRepository.findByIdAndUserId("t1", "u1"))
                .thenReturn(Optional.of(txn("t1", "u1", 999, "USD")));

        CreateContributionRequest req = new CreateContributionRequest();
        req.setAmount(40_000);
        req.setTransactionId("t1");

        assertThrows(BadRequestException.class, () -> goalService.addContribution("g1", req));
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void addContribution_rejects_whenLinkedTransactionNotFound() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(transactionRepository.findByIdAndUserId("t1", "u1")).thenReturn(Optional.empty());

        CreateContributionRequest req = new CreateContributionRequest();
        req.setAmount(40_000);
        req.setTransactionId("t1");

        assertThrows(NotFoundException.class, () -> goalService.addContribution("g1", req));
    }

    @Test
    void deleteContribution_dropsFromAchieved_whenBelowTargetAgain() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACHIEVED, 100_000);
        GoalContribution c = new GoalContribution();
        c.setId("c1");
        c.setGoalId("g1");
        c.setAmount(60_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.findByIdAndGoalId("c1", "g1")).thenReturn(Optional.of(c));
        when(contributionRepository.sumAmountByGoalId("g1")).thenReturn(40_000L);

        goalService.deleteContribution("g1", "c1");

        verify(contributionRepository).delete(c);
        assertEquals(GoalStatus.ACTIVE, g.getStatus());
        verify(goalRepository).save(g);
    }

    @Test
    void deleteContribution_rejects_whenNotInGoal() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.findByIdAndGoalId("c9", "g1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> goalService.deleteContribution("g1", "c9"));
        verify(contributionRepository, never()).delete(any());
    }

    @Test
    void delete_blocked_whenGoalHasContributions() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        GoalContribution c = new GoalContribution();
        c.setGoalId("g1");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.findByGoalId("g1")).thenReturn(List.of(c));

        assertThrows(BadRequestException.class, () -> goalService.delete("g1"));
        verify(goalRepository, never()).delete(any());
    }

    @Test
    void delete_hardDeletes_whenNoContributions() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACTIVE, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.findByGoalId("g1")).thenReturn(List.of());

        goalService.delete("g1");

        verify(goalRepository).delete(g);
    }

    @Test
    void update_raisingTarget_unachievesGoal() {
        SavingsGoal g = goal("g1", "u1", GoalStatus.ACHIEVED, 100_000);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("g1", "u1")).thenReturn(Optional.of(g));
        when(contributionRepository.sumAmountByGoalId("g1")).thenReturn(100_000L);

        UpdateGoalRequest req = new UpdateGoalRequest();
        req.setTargetAmount(250_000L);
        GoalResponse resp = goalService.update("g1", req);

        assertEquals(250_000L, resp.getTargetAmount());
        assertEquals(GoalStatus.ACTIVE, resp.getStatus());   // saved 100k < new target
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(goalRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> goalService.get("x"));
    }
}
