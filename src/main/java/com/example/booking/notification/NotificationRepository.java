package com.example.booking.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query(value = """
            INSERT INTO notifications (event_id, user_id, type, message)
            VALUES (:eventId, :userId, :type, :message)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("userId") Long userId,
                       @Param("type") String type, @Param("message") String message);
}