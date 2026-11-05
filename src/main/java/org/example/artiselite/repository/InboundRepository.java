package org.example.artiselite.repository;

import org.example.artiselite.entity.Inbound;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface InboundRepository extends JpaRepository<Inbound, Long> {
    List<Inbound> findByReceivedDate(LocalDate date);
    List<Inbound> findByProductId(Long productId);

    @Query("SELECT COUNT(i) FROM Inbound i WHERE i.receivedDate = :date")
    Long countByDate(LocalDate date);

    // ---- Agent tool queries (read-only). Empty string = no filter.

    @Query("SELECT COUNT(i) AS transactionCount, SUM(i.quantity) AS totalUnits, " +
            "SUM(i.unitCost * i.quantity) AS totalValue " +
            "FROM Inbound i WHERE i.isDeleted = false AND i.receivedDate BETWEEN :from AND :to " +
            "AND (:sku = '' OR LOWER(i.product.sku) = LOWER(:sku)) " +
            "AND (:category = '' OR LOWER(i.product.category) = LOWER(:category))")
    MovementTotals sumMovements(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("sku") String sku, @Param("category") String category);

    @Query("SELECT i FROM Inbound i JOIN FETCH i.product p WHERE i.isDeleted = false " +
            "AND i.receivedDate BETWEEN :from AND :to " +
            "AND (:sku = '' OR LOWER(p.sku) = LOWER(:sku)) " +
            "AND (:category = '' OR LOWER(p.category) = LOWER(:category)) " +
            "ORDER BY i.receivedDate DESC, i.id DESC")
    List<Inbound> findMovements(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("sku") String sku, @Param("category") String category,
                                Pageable pageable);
}
