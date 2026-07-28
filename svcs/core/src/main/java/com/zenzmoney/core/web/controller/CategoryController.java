package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.CategoryService;
import com.zenzmoney.core.web.dto.CategoryResponse;
import com.zenzmoney.core.web.dto.CreateCategoryRequest;
import com.zenzmoney.core.web.dto.UpdateCategoryRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/categories")
@RolesAllowed({"USER", "ADMIN"})
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(@PathVariable String id,
                                                                @Valid @RequestBody UpdateCategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Category deleted")));
    }

    /** Provision the default category set (onboarding). Idempotent. */
    @PostMapping("/seed-defaults")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> seedDefaults() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.seedDefaults()));
    }
}
