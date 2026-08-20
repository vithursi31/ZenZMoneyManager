package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.web.dto.CategoryResponse;
import com.zenzmoney.core.web.dto.CreateCategoryRequest;
import com.zenzmoney.core.web.dto.UpdateCategoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * CRUD for {@link Category}, scoped to the authenticated user (§1.5). Enforces the
 * one-level hierarchy and same-kind rules in the service layer, guards deletion of
 * referenced categories, and provides the onboarding seed set (F-1.27).
 */
@Service
public class CategoryService {

    /** Mutations only. Reads are already covered by the per-request line MdcContextFilter writes. */
    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /** Default categories seeded at onboarding (§1.5). */
    private static final List<String> SEED_INCOME = List.of(
            "Salary", "Business", "Freelance", "Investments", "Gifts");
    private static final List<String> SEED_EXPENSE = List.of(
            "Food & Drinks", "Groceries", "Transport", "Housing", "Utilities",
            "Entertainment", "Health", "Shopping", "Education", "Subscriptions", "Other");

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CurrentUserService currentUser;

    public CategoryService(CategoryRepository categoryRepository,
                           TransactionRepository transactionRepository,
                           BudgetRepository budgetRepository,
                           CurrentUserService currentUser) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public CategoryResponse create(CreateCategoryRequest req) {
        String userId = currentUser.requireUserId();

        String parentId = null;
        if (req.getParentId() != null && !req.getParentId().isBlank()) {
            Category parent = requireOwned(req.getParentId(), userId);
            if (parent.getParentId() != null) {
                throw new BadRequestException("Sub-categories are only one level deep.");
            }
            if (parent.getKind() != req.getKind()) {
                throw new BadRequestException("A sub-category must have the same kind as its parent.");
            }
            parentId = parent.getId();
        }

        Category category = new Category();
        category.setUserId(userId);
        category.setName(req.getName().trim());
        category.setKind(req.getKind());
        category.setParentId(parentId);
        category.setColor(req.getColor());
        category.setIcon(req.getIcon());
        category.setSortOrder(req.getSortOrder());
        Category saved = categoryRepository.save(category);
        log.info("Category created: {} kind={} parent={} (category {}, user {})",
                saved.getName(), saved.getKind(),
                saved.getParentId() == null ? "none" : saved.getParentId(),
                saved.getId(), userId);
        return CategoryResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        String userId = currentUser.requireUserId();
        return categoryRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(Category::getKind)
                        .thenComparingInt(Category::getSortOrder)
                        .thenComparing(Category::getName))
                .map(CategoryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(String id) {
        return CategoryResponse.of(requireOwned(id, currentUser.requireUserId()));
    }

    @Transactional
    public CategoryResponse update(String id, UpdateCategoryRequest req) {
        Category category = requireOwned(id, currentUser.requireUserId());
        if (req.getName() != null && !req.getName().isBlank()) {
            category.setName(req.getName().trim());
        }
        if (req.getColor() != null) category.setColor(req.getColor());
        if (req.getIcon() != null) category.setIcon(req.getIcon());
        if (req.getSortOrder() != null) category.setSortOrder(req.getSortOrder());
        log.info("Category updated: {} (category {}, user {})",
                category.getName(), id, category.getUserId());
        return CategoryResponse.of(categoryRepository.save(category));
    }

    /**
     * Deletes a category only when nothing references it — no sub-categories, no
     * transactions, no budgets (§1.5). A referenced category should be left unused
     * or (Phase 2) merged.
     */
    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Category category = requireOwned(id, userId);
        if (categoryRepository.existsByUserIdAndParentId(userId, id)) {
            throw new BadRequestException("Category has sub-categories; delete or move them first.");
        }
        if (transactionRepository.existsByCategoryId(id)) {
            throw new BadRequestException("Category is used by transactions and cannot be deleted.");
        }
        if (budgetRepository.existsByCategoryIdAndStatusNot(id, BudgetStatus.DELETED)) {
            throw new BadRequestException("Category is used by a budget and cannot be deleted.");
        }
        categoryRepository.delete(category);
        // Hard delete, allowed only because nothing referenced it — this line is the last record.
        log.info("Category deleted: {} kind={} (category {}, user {})",
                category.getName(), category.getKind(), id, userId);
    }

    /**
     * Provisions the default category set for a user the first time (onboarding,
     * F-1.27). Idempotent: if the user already has any category, it returns the
     * existing set unchanged rather than duplicating.
     */
    @Transactional
    public List<CategoryResponse> seedDefaults() {
        String userId = currentUser.requireUserId();
        if (categoryRepository.existsByUserId(userId)) {
            log.debug("Default categories not seeded for user {} — it already has categories", userId);
            return list();
        }
        int order = 0;
        for (String name : SEED_INCOME) {
            save(userId, name, CategoryKind.INCOME, order++);
        }
        order = 0;
        for (String name : SEED_EXPENSE) {
            save(userId, name, CategoryKind.EXPENSE, order++);
        }
        log.info("Seeded {} default categories for user {}",
                SEED_INCOME.size() + SEED_EXPENSE.size(), userId);
        return list();
    }

    private void save(String userId, String name, CategoryKind kind, int sortOrder) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        c.setKind(kind);
        c.setSortOrder(sortOrder);
        categoryRepository.save(c);
    }

    private Category requireOwned(String id, String userId) {
        return categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Category not found"));
    }
}
