package com.nextrayvision.teslatracking.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OdometerReadingRepository extends JpaRepository<OdometerReading, Long> {

    List<OdometerReading> findByVinOrderByRecordedAtAsc(String vin);

    Optional<OdometerReading> findTopByVinOrderByRecordedAtDesc(String vin);
}
