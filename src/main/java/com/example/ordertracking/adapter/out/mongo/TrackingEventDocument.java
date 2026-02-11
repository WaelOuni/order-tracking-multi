package com.example.ordertracking.adapter.out.mongo;

import java.time.Instant;

public class TrackingEventDocument {
    private String orderId;
    private String status;
    private Instant occurredAt;
    private String note;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
