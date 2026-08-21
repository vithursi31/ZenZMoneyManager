package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.repository.BudgetRepository;
import com.zenzmoney.core.repository.CategoryRepository;
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
    private final BudgetRepository budgetRepository;
    private final CurrentUserService currentUser;

    public CategoryService(CategoryRepository categoryRepository,
                           BudgetRepository budgetRepository,
                           CurrentUserService currentUser) {
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public CategoryResponse create(CreateCategoryRequest req) {
        String userId = currentUser.requireUserId();

        String parentId = null;
        if (req.getParentId() != null && !req.getParentId().isBlank()) {
            Category parent = requireLive(req.getParentId(), userId);
            if (parent.getParentId() != null) {
                throw new BadRequestException(Msg.CATEGORY_DEPTH_EXCEEDED);
            }
            if (parent.getKind() != req.getKind()) {
                throw new BadRequestException(Msg.CATEGORY_PARENT_KIND);
            }
            parentId = parent.getId();
        }

        String name = req.getName().trim();
        requireNameFree(userId, req.getKind(), name, null);

        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
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
        return categoryRepository.findByUserIdAndStatus(userId, CategoryStatus.ACTIVE).stream()
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
        Category category = requireLive(id, currentUser.requireUserId());
        if (req.getName() != null && !req.getName().isBlank()) {
            String name = req.getName().trim();
            requireNameFree(category.getUserId(), category.getKind(), name, category.getId());
            category.setName(name);
        }
        if (req.getColor() != null) category.setColor(req.getColor());
        if (req.getIcon() != null) category.setIcon(req.getIcon());
        if (req.getSortOrder() != null) category.setSortOrder(req.getSortOrder());
        log.info("Category updated: {} (category {}, user {})",
                category.getName(), id, category.getUserId());
        return CategoryResponse.of(categoryRepository.save(category));
    }

    /**
     * Soft delete: the row stays, its status changes. Transactions already filed under
     * the category keep pointing at it — that is the whole reason this is not a row
     * removal, since a past month's breakdown still has to name where the money went.
     * The category leaves every picker and its name frees up for reuse.
     *
     * <p>Still refused while something would be left dangling: a live sub-category
     * (which would be orphaned) or a live budget (which would go on measuring spend
     * against a category the user can no longer file anything under).
     */
    @Transactional
    public void delete(String id) {
        String userId = currentUser.requireUserId();
        Category category = requireOwned(id, userId);
        if (category.getStatus() == CategoryStatus.DELETED) {
            throw new BadRequestException(Msg.CATEGORY_ALREADY_DELETED);
        }
        if (categoryRepository.existsByUserIdAndParentIdAndStatus(userId, id, CategoryStatus.ACTIVE)) {
            throw new BadRequestException(Msg.CATEGORY_HAS_CHILDREN);
        }
        if (budgetRepository.existsByCategoryIdAndStatusNot(id, BudgetStatus.DELETED)) {
            throw new BadRequestException(Msg.CATEGORY_USED_BY_BUDGET);
        }
        category.setStatus(CategoryStatus.DELETED);
        categoryRepository.save(category);
        log.info("Category deleted (soft): {} kind={} (category {}, user {})",
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
        if (categoryRepository.existsByUserIdAndStatus(userId, CategoryStatus.ACTIVE)) {
            log.debug("Default categories not seeded for user {} — it already has live categories", userId);
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
                .orElseThrow(() -> new NotFoundException(Msg.CATEGORY_NOT_FOUND));
    }

    /** Owned and not deleted — a deleted category is history, not something to build on. */
    private Category requireLive(String id, String userId) {
        Category category = requireOwned(id, userId);
        if (category.getStatus() == CategoryStatus.DELETED) {
            throw new BadRequestException(Msg.CATEGORY_DELETED);
        }
        return category;
    }

    /**
     * One live category per (user, kind, name), compared case-insensitively — Food,
     * food and FOOD are the same category. Scoped to the kind so "Gifts" can exist as
     * both income and expense, which is a real distinction and never ambiguous: a
     * transaction's category has to match its type, so a picker only ever shows one
     * kind. {@code excludeId} lets a rename keep its own name (a change of case).
     */
    private void requireNameFree(String userId, CategoryKind kind, String name, String excludeId) {
        boolean taken = categoryRepository
                .findByUserIdAndKindAndNameIgnoreCaseAndStatus(userId, kind, name, CategoryStatus.ACTIVE)
                .stream()
                .anyMatch(c -> !c.getId().equals(excludeId));
        if (taken) {
            throw new BadRequestException(Msg.CATEGORY_DUPLICATE, name);
        }
    }
}
