package com.jinjing.banking.modules.transaction.service;

import com.jinjing.banking.modules.transaction.entity.OutboxEvent;
import com.jinjing.banking.modules.transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public boolean existsByClientRequestId(String clientRequestId) {
        return outboxEventRepository.existsByClientRequestId(clientRequestId);
    }

    /**
     * 受理结果查询：requestId 还在 Outbox 里说明尚未被消费者占坑（状态 QUEUED）。
     */
    public java.util.Optional<OutboxEvent> findByClientRequestId(String clientRequestId) {
        return outboxEventRepository.findByClientRequestId(clientRequestId);
    }

    public void save(OutboxEvent event) {
        outboxEventRepository.save(event);
    }
}
