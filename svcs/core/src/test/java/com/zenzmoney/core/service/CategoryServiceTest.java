package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.web.dto.CreateCategoryRequest;
import com.zenzmoney.core.web.dto.UpdateCategoryRequest;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
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
        c.setStatus(CategoryStatus.ACTIVE);
        return c;
    }

    private Category named(String id, String userId, CategoryKind kind, String name) {
        Category c = cat(id, userId, kind, null);
        c.setName(name);
        return c;
    }

    private CreateCategoryRequest req(String name, CategoryKind kind, String parentId) {
        CreateCategoryRequest r = new CreateCategoryRequest();
        r.setName(name);
        r.setKind(kind);
        r.setParentId(parentId);
        return r;
    }

    /** No live category holds the name being asked for. */
    private void nameFree(CategoryKind kind) {
        when(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                eq("u1"), eq(kind), anyString(), eq(CategoryStatus.ACTIVE))).thenReturn(List.of());
    }

    @Test
    void create_topLevel_ok() {
        when(currentUser.requireUserId()).thenReturn("u1");
        nameFree(CategoryKind.EXPENSE);
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = categoryService.create(req("Food & Drinks", CategoryKind.EXPENSE, null));

        assertEquals("Food & Drinks", resp.getName());
        assertEquals(CategoryKind.EXPENSE, resp.getKind());
        assertEquals(CategoryStatus.ACTIVE, resp.getStatus());
    }

    @Test
    void create_subCategory_ok_whenParentValidAndSameKind() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("p1", "u1"))
                .thenReturn(Optional.of(cat("p1", "u1", CategoryKind.EXPENSE, null)));
        nameFree(CategoryKind.EXPENSE);
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
    void create_subCategory_rejected_whenParentIsDeleted() {
        Category parent = cat("p1", "u1", CategoryKind.EXPENSE, null);
        parent.setStatus(CategoryStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("p1", "u1")).thenReturn(Optional.of(parent));

        assertThrows(BadRequestException.class,
                () -> categoryService.create(req("Coffee", CategoryKind.EXPENSE, "p1")));
        verify(categoryRepository, never()).save(any());
    }

    // --- one live category per (user, kind, name), case-insensitively ---

    @Test
    void create_rejected_whenNameAlreadyTaken() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                "u1", CategoryKind.EXPENSE, "FOOD", CategoryStatus.ACTIVE))
                .thenReturn(List.of(named("c1", "u1", CategoryKind.EXPENSE, "Food")));

        assertThrows(BadRequestException.class,
                () -> categoryService.create(req("FOOD", CategoryKind.EXPENSE, null)));
        verify(categoryRepository, never()).save(any());
    }

    /** The name is trimmed before the collision check, so " Food " cannot slip past it. */
    @Test
    void create_checksTheTrimmedName() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                "u1", CategoryKind.EXPENSE, "Food", CategoryStatus.ACTIVE))
                .thenReturn(List.of(named("c1", "u1", CategoryKind.EXPENSE, "Food")));

        assertThrows(BadRequestException.class,
                () -> categoryService.create(req("  Food  ", CategoryKind.EXPENSE, null)));
    }

    @Test
    void update_rejected_whenRenamingOntoAnotherCategorysName() {
        Category food = named("c1", "u1", CategoryKind.EXPENSE, "Food");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(food));
        when(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                "u1", CategoryKind.EXPENSE, "Groceries", CategoryStatus.ACTIVE))
                .thenReturn(List.of(named("c2", "u1", CategoryKind.EXPENSE, "Groceries")));

        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setName("Groceries");
        assertThrows(BadRequestException.class, () -> categoryService.update("c1", req));
        verify(categoryRepository, never()).save(any());
    }

    /** Recasing its own name is not a collision with itself. */
    @Test
    void update_allowsACategoryToRecaseItsOwnName() {
        Category food = named("c1", "u1", CategoryKind.EXPENSE, "food");
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(food));
        when(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                "u1", CategoryKind.EXPENSE, "Food", CategoryStatus.ACTIVE))
                .thenReturn(List.of(food));
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setName("Food");

        assertEquals("Food", categoryService.update("c1", req).getName());
    }

    @Test
    void update_rejected_whenCategoryIsDeleted() {
        Category deleted = named("c1", "u1", CategoryKind.EXPENSE, "Food");
        deleted.setStatus(CategoryStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(deleted));

        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setName("Food & Drinks");
        assertThrows(BadRequestException.class, () -> categoryService.update("c1", req));
        verify(categoryRepository, never()).save(any());
    }

    // --- delete: soft, so last month's transactions keep their category ---

    @Test
    void delete_isSoft_setsStatusDeletedAndKeepsTheRow() {
        Category c = cat("c1", "u1", CategoryKind.EXPENSE, null);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(c));
        when(categoryRepository.existsByUserIdAndParentIdAndStatus("u1", "c1", CategoryStatus.ACTIVE))
                .thenReturn(false);
        when(budgetRepository.existsByCategoryIdAndStatusNot("c1", BudgetStatus.DELETED)).thenReturn(false);

        categoryService.delete("c1");

        assertEquals(CategoryStatus.DELETED, c.getStatus());
        verify(categoryRepository).save(c);
        verify(categoryRepository, never()).delete(any());
    }

    /**
     * The reason delete went soft: a category with months of transactions behind it
     * must still be removable from the picker.
     */
    @Test
    void delete_isNotBlockedByExistingTransactions() {
        Category c = cat("c1", "u1", CategoryKind.EXPENSE, null);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(c));
        when(categoryRepository.existsByUserIdAndParentIdAndStatus("u1", "c1", CategoryStatus.ACTIVE))
                .thenReturn(false);
        when(budgetRepository.existsByCategoryIdAndStatusNot("c1", BudgetStatus.DELETED)).thenReturn(false);

        categoryService.delete("c1");   // no transaction guard is consulted at all

        assertEquals(CategoryStatus.DELETED, c.getStatus());
    }

    @Test
    void delete_rejected_whenAlreadyDeleted() {
        Category c = cat("c1", "u1", CategoryKind.EXPENSE, null);
        c.setStatus(CategoryStatus.DELETED);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1")).thenReturn(Optional.of(c));

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void delete_blocked_whenHasLiveChildren() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.existsByUserIdAndParentIdAndStatus("u1", "c1", CategoryStatus.ACTIVE))
                .thenReturn(true);

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void delete_blocked_whenUsedByBudget() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("c1", "u1"))
                .thenReturn(Optional.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));
        when(categoryRepository.existsByUserIdAndParentIdAndStatus("u1", "c1", CategoryStatus.ACTIVE))
                .thenReturn(false);
        when(budgetRepository.existsByCategoryIdAndStatusNot("c1", BudgetStatus.DELETED)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> categoryService.delete("c1"));
        verify(categoryRepository, never()).save(any());
    }

    // --- listing and seeding see live categories only ---

    @Test
    void list_returnsLiveCategoriesOnly() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));

        assertEquals(1, categoryService.list().size());
        verify(categoryRepository, never()).findByUserId(anyString());
    }

    @Test
    void seedDefaults_createsFullSet_whenUserHasNone() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.existsByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(false);
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        categoryService.seedDefaults();

        // 5 income + 11 expense = 16 default categories
        verify(categoryRepository, times(16)).save(any());
    }

    @Test
    void seedDefaults_noOp_whenUserAlreadyHasLiveCategories() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.existsByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(true);
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(cat("c1", "u1", CategoryKind.EXPENSE, null)));

        categoryService.seedDefaults();

        verify(categoryRepository, never()).save(any());
    }

    /** A user who deleted every category can seed again — the check is on live rows. */
    @Test
    void seedDefaults_seedsAgain_whenEveryCategoryWasDeleted() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.existsByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(false);
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE)).thenReturn(List.of());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        categoryService.seedDefaults();

        verify(categoryRepository, times(16)).save(any());
    }

    @Test
    void get_notOwned_throwsNotFound() {
        when(currentUser.requireUserId()).thenReturn("u1");
        when(categoryRepository.findByIdAndUserId("x", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> categoryService.get("x"));
    }
}
