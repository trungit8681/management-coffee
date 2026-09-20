package com.coffee.management.loyalty.domain;
public record Points(long value) {
    public Points { if (value<=0) throw new IllegalArgumentException("Points must be positive"); }
}
