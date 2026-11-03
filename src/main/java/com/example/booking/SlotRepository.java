package com.example.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    @Query(value = """
            SELECT * FROM slots s
            WHERE s.resource_id = :resourceId
              AND NOT EXISTS (SELECT 1 FROM bookings b
                              WHERE b.slot_id = s.id AND b.status = 'CONFIRMED')
            ORDER BY s.start_time
            """, nativeQuery = true)
    List<Slot> findAvailable(@Param("resourceId") Long resourceId);
}
