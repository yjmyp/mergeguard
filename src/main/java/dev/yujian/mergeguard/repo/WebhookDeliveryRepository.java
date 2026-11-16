package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {
}
