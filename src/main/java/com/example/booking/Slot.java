package com.example.booking;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "slots")
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    protected Slot() {}

    public Slot(Long resourceId, Instant startTime, Instant endTime) {
        this.resourceId = resourceId;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getId() { return id; }
    public Long getResourceId() { return resourceId; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
}
