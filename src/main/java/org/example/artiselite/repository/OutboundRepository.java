package org.example.artiselite.repository;

import org.springframework.stereotype.Repository;
import org.example.artiselite.entity.Outbound;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;


@Repository
public interface OutboundRepository extends JpaRepository<Outbound, Long>{
    List<Outbound> findByDispatchDate(LocalDate date);
    List<Outbound> findByProductId(Long productId);

    @Query("SELECT COUNT(o) FROM Outbound o WHERE o.dispatchDate = :date")
    Long countByDate(LocalDate date);

    @Query("SELECT o FROM Outbound o WHERE o.product.id = :productId " +
            "ORDER BY o.dispatchDate DESC")
    List<Outbound> findRecentOutboundsByProduct(Long productId);

    // ---- Agent tool queries (read-only). Empty string = no filter.

    @Query("SELECT COUNT(o) AS transactionCount, SUM(o.quantity) AS totalUnits, SUM(o.totalValue) AS totalValue " +
            "FROM Outbound o WHERE o.isDeleted = false AND o.dispatchDate BETWEEN :from AND :to " +
            "AND (:sku = '' OR LOWER(o.product.sku) = LOWER(:sku)) " +
            "AND (:category = '' OR LOWER(o.product.category) = LOWER(:category))")
    MovementTotals sumMovements(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("sku") String sku, @Param("category") String category);

    @Query("SELECT o FROM Outbound o JOIN FETCH o.product p WHERE o.isDeleted = false " +
            "AND o.dispatchDate BETWEEN :from AND :to " +
            "AND (:sku = '' OR LOWER(p.sku) = LOWER(:sku)) " +
            "AND (:category = '' OR LOWER(p.category) = LOWER(:category)) " +
            "ORDER BY o.dispatchDate DESC, o.id DESC")
    List<Outbound> findMovements(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("sku") String sku, @Param("category") String category,
                                 Pageable pageable);
}
