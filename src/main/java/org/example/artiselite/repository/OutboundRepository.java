package org.example.artiselite.repository;

import org.springframework.stereotype.Repository;
import org.example.artiselite.entity.Outbound;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
