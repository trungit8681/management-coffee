package com.coffee.management.organization.domain.model;

import java.time.Duration;
import java.time.Instant;

public record WorkPeriod(Instant startsAt, Instant endsAt) {
    public WorkPeriod {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Invalid work period");
        if (Duration.between(startsAt, endsAt).compareTo(Duration.ofHours(24)) > 0) throw new IllegalArgumentException("A shift cannot exceed 24 hours");
    }
    public boolean overlaps(WorkPeriod other) { return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt); }
}
