package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {

    List<Category> findByUserId(String userId);

    Optional<Category> findByIdAndUserId(String id, String userId);

    List<Category> findByUserIdAndParentId(String userId, String parentId);
}
