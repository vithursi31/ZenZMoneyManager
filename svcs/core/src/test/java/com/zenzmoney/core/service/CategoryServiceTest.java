package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CreateCategoryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock BudgetRepository budgetRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks CategoryService categoryService;

    private Category cat(String id, String userId, CategoryKind kind, String parentId) {
        Category c = new Category();
        c.setId(id);
        c.setUserId(userId);
        c.setName("Cat-" + id);
        c.setKind(kind);
        c.setParentId(parentId);
        return c;
    }

    private CreateCategoryRequest req(String name, CategoryKind kind, String parentId) {
        CreateCategoryRequest r = new CreateCategoryRequest();
        r.setName(name);
        r.setKind(kind);
        r.setParentId(parentId);
        return r;
    }

    @Test
    void create_topLevel_ok() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = categoryService.create(req("Food & Drinks", CategoryKind.EXPENSE, null));

        assertEquals("Food & Drinks", resp.getName());
        assertEquals(CategoryKind.EXPENSE, resp.getKind());
    }

    @Test
    void create_subCategory_ok_whenParentValidAndSameKind() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("p1", "u1"))
                .thenReturn(Optional.of(cat("p1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = categoryService.create(req("Coffee", CategoryKind.EXPENSE, "p1"));

        assertEquals("p1", resp.getParentId());
    }

    @Test
    void create_subCategory_rejected_whenParentAlreadyHasParent() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("p1", "u1"))
                .thenReturn(Optional.of(cat("p1", "u1", CategoryKind.EXPENSE, "grandparent")));

        assertThrows(BadRequestException.class,
                () -> categoryService.create(req("Coffee", CategoryKind.EXPENSE, "p1")));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void create_subCategory_rejected_whenKindMismatch() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("p1", "u1"))
                .thenReturn(Optional.of(cat("p1", "u1", CategoryKind.INCOME, null)));

        assertThrows(BadRequestException.class,
                () -> categoryService.create(req("Coffee", CategoryKind.EXPENSE, "p1")));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void delete_blocked_whenHasChildren() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.existsByUserIdAndParentId("u1", "c1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void delete_blocked_whenUsedByTransaction() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.existsByUserIdAndParentId("u1", "c1")).thenReturn(false);
        when(transactionRepository.existsByCategoryId("c1")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void delete_blocked_whenUsedByBudget() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.existsByUserIdAndParentId("u1", "c1")).thenReturn(false);
        when(transactionRepository.existsByCategoryId("c1")).thenReturn(false);
        when(budgetRepository.existsByCategoryIdAndStatusNot("c1", BudgetStatus.DELETED)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void delete_succeeds_whenUnreferenced() {
        Category c = cat("c1", "u1", CategoryKind.EXPENSE, null);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(c));
        when(categoryRepository.existsByUserIdAndParentId("u1", "c1")).thenReturn(false);
        when(transactionRepository.existsByCategoryId("c1")).thenReturn(false);
        when(budgetRepository.existsByCategoryIdAndStatusNot("c1", BudgetStatus.DELETED)).thenReturn(false);

        categoryService.delete("c1");

        verify(categoryRepository).delete(c);
    }

    @Test
    void seedDefaults_createsFullSet_whenUserHasNone() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.existsByUserId("u1")).thenReturn(false);
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of());   // list() after seeding
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        categoryService.seedDefaults();

        // 5 income + 11 expense = 16 default categories
        verify(categoryRepository, times(16)).save(any());
    }

    @Test
    void seedDefaults_noOp_whenUserAlreadyHasCategories() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.existsByUserId("u1")).thenReturn(true);
        when(categoryRepository.findByUserId("u1"))
                .thenReturn(List.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));

        categoryService.seedDefaults();

        verify(categoryRepository, never()).save(any());
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> categoryService.get("x"));
    }
}
