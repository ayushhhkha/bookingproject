package com.example.booking.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "user_id")
    private Long userId;

    private String type;

    private String message;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Notification() {}

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Long getUserId() { return userId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}