package org.example.artiselite.repository;

import org.example.artiselite.entity.Inbound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface InboundRepository extends JpaRepository<Inbound, Long> {
    List<Inbound> findByReceivedDate(LocalDate date);
    List<Inbound> findByProductId(Long productId);

    @Query("SELECT COUNT(i) FROM Inbound i WHERE i.receivedDate = :date")
    Long countByDate(LocalDate date);
}
