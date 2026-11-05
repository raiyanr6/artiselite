package org.example.artiselite.repository;

import org.example.artiselite.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);
    Optional<Product> findByIdAndIsDeletedFalse(Long id);
    boolean existsBySkuIgnoreCase(String sku);

    @Query("SELECT p FROM Product p WHERE p.isDeleted = false AND p.isArchived = false")
    List<Product> findActiveProducts();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.isDeleted = false AND p.isArchived = false")
    long countActiveProducts();

    @Query("SELECT p FROM Product p WHERE p.quantity <= p.lowStockThreshold " +
            "AND p.isDeleted = false AND p.isArchived = false")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.isDeleted = false AND p.isArchived = false " +
            "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(p.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Product> searchProducts(String keyword);

    // ---- Agent tool queries (read-only). An empty string means "no filter"; it avoids
    // binding NULL parameters, which PostgreSQL can't always infer a type for.

    @Query("SELECT p FROM Product p WHERE p.isDeleted = false AND p.isArchived = false " +
            "AND (:category = '' OR LOWER(p.category) = LOWER(:category)) " +
            "AND (:keyword = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(p.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Product> searchActive(@Param("keyword") String keyword, @Param("category") String category,
                               Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isDeleted = false AND p.isArchived = false " +
            "AND p.quantity <= p.lowStockThreshold " +
            "AND (:category = '' OR LOWER(p.category) = LOWER(:category)) " +
            "ORDER BY (p.lowStockThreshold - p.quantity) DESC, p.sku")
    Page<Product> findLowStock(@Param("category") String category, Pageable pageable);

    @Query("SELECT DISTINCT p.category FROM Product p WHERE p.isDeleted = false AND p.isArchived = false " +
            "ORDER BY p.category")
    List<String> findActiveCategories();
}
