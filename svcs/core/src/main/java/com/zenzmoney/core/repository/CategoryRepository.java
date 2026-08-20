package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.core.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /**
     * Every category the user has ever had, deleted ones included — for labelling
     * history (a past month's breakdown still needs the name of a category the user
     * has since removed). To offer categories to choose from, use the
     * {@code ...AndStatus} variants instead.
     */
    List<Category> findByUserId(String userId);

    Optional<Category> findByIdAndUserId(String id, String userId);

    List<Category> findByUserIdAndParentId(String userId, String parentId);

    /** The categories a user can still pick from. */
    List<Category> findByUserIdAndStatus(String userId, CategoryStatus status);

    /** One category, only if it is still live — the lookup every write path wants. */
    Optional<Category> findByIdAndUserIdAndStatus(String id, String userId, CategoryStatus status);

    /**
     * Name collisions within one kind, compared case-insensitively — the "Food is
     * FOOD" rule (§1.5). Backed by {@code uq_category_name_per_kind}.
     */
    List<Category> findByUserIdAndKindAndNameIgnoreCaseAndStatus(
            String userId, CategoryKind kind, String name, CategoryStatus status);

    /** True if the user has any live sub-category under this parent. */
    boolean existsByUserIdAndParentIdAndStatus(String userId, String parentId, CategoryStatus status);

    /** For seed-defaults idempotency: does the user have any live category yet? */
    boolean existsByUserIdAndStatus(String userId, CategoryStatus status);
}
