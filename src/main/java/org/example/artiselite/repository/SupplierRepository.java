package org.example.artiselite.repository;

import org.example.artiselite.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long>{
    List<Supplier> findByIsActiveTrue();
}
