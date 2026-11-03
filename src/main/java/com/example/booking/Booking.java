package com.example.booking;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    public enum Status { CONFIRMED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id")
    private Long slotId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.CONFIRMED;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected Booking() {}

    public Booking(Long slotId, Long userId) {
        this.slotId = slotId;
        this.userId = userId;
    }

    public void cancel() { this.status = Status.CANCELLED; }

    public Long getId() { return id; }
    public Long getSlotId() { return slotId; }
    public Long getUserId() { return userId; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
