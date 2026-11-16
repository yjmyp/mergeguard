package dev.yujian.mergeguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mg_webhook_delivery")
public class WebhookDelivery {

    @Id
    @Column(name = "delivery_id", length = 64)
    private String deliveryId;

    @Column(length = 60)
    private String event;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
