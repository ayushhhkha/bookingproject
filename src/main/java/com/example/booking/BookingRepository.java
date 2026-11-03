package com.example.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    long countBySlotIdAndStatus(Long slotId, Booking.Status status);
    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
}
