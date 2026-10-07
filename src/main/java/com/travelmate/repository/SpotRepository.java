package com.travelmate.repository;

import com.travelmate.domain.Spot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpotRepository extends JpaRepository<Spot, Long> {
    List<Spot> findByCityKey(String cityKey);
    java.util.Optional<Spot> findByExternalId(String externalId);
}
