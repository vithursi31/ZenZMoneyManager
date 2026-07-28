package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Payee;
import com.zenzmoney.core.repository.PayeeRepository;
import com.zenzmoney.core.repository.RecurringTransactionRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayeeServiceTest {

    @Mock PayeeRepository payeeRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RecurringTransactionRepository recurringRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks PayeeService payeeService;

    private Payee payee(String id, String userId, String name, String normalized) {
        Payee p = new Payee();
        p.setId(id);
        p.setUserId(userId);
        p.setName(name);
        p.setNormalizedName(normalized);
        return p;
    }

    @Test
    void resolveOrCreate_returnsNull_forBlankName() {
        assertNull(payeeService.resolveOrCreate("u1", "  "));
        verify(payeeRepository, never()).save(any());
    }

    @Test
    void resolveOrCreate_reusesExisting_whenNormalizedMatches() {
        // "  Keells  Super " normalizes to "keells super"
        when(payeeRepository.findByUserIdAndNormalizedName("u1", "keells super"))
                .thenReturn(Optional.of(payee("p1", "u1", "Keells Super", "keells super")));

        String id = payeeService.resolveOrCreate("u1", "  Keells  Super ");

        assertEquals("p1", id);
        verify(payeeRepository, never()).save(any());
    }

    @Test
    void resolveOrCreate_createsNew_whenNoMatch() {
        when(payeeRepository.findByUserIdAndNormalizedName("u1", "keells"))
                .thenReturn(Optional.empty());
        when(payeeRepository.save(any())).thenAnswer(inv -> {
            Payee p = inv.getArgument(0);
            p.setId("new1");
            return p;
        });

        String id = payeeService.resolveOrCreate("u1", "Keells");

        assertEquals("new1", id);
        ArgumentCaptor<Payee> saved = ArgumentCaptor.forClass(Payee.class);
        verify(payeeRepository).save(saved.capture());
        assertEquals("Keells", saved.getValue().getName());          // display name preserved
        assertEquals("keells", saved.getValue().getNormalizedName()); // dedup key normalized
        assertEquals("u1", saved.getValue().getUserId());
    }

    @Test
    void delete_blocked_whenReferencedByTransaction() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(payeeRepository.findByIdAndUserId("p1", "u1"))
                .thenReturn(Optional.of(payee("p1", "u1", "Keells", "keells")));
        when(transactionRepository.existsByPayeeId("p1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> payeeService.delete("p1"));
        verify(payeeRepository, never()).delete(any());
    }

    @Test
    void delete_succeeds_whenUnreferenced() {
        Payee p = payee("p1", "u1", "Keells", "keells");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(payeeRepository.findByIdAndUserId("p1", "u1")).thenReturn(Optional.of(p));
        when(transactionRepository.existsByPayeeId("p1")).thenReturn(false);
        when(recurringRepository.existsByPayeeId("p1")).thenReturn(false);

        payeeService.delete("p1");

        verify(payeeRepository).delete(p);
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(payeeRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> payeeService.get("x"));
    }
}
