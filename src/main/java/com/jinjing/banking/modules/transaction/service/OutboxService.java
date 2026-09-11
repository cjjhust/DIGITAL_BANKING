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

    public void save(OutboxEvent event) {
        outboxEventRepository.save(event);
    }
}
