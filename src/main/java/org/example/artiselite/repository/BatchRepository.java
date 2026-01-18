package org.example.artiselite.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.example.artiselite.entity.Batch;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {
    List<Batch> findByProductIdAndQuantityGreaterThan(Long productId, Integer quantity);

    @Query("SELECT b FROM Batch b WHERE b.product.id = :productId " +
            "AND b.quantity > 0 AND b.isExpired = false " +
            "ORDER BY b.expiryDate ASC, b.receivedDate ASC")
    List<Batch> findAvailableBatchesFIFO(Long productId);

    @Query("SELECT b FROM Batch b WHERE b.expiryDate < :date AND b.isExpired = false")
    List<Batch> findExpiredBatches(LocalDate date);
}
