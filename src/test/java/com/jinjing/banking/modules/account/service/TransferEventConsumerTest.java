package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.modules.account.dto.TransferRequest;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
/**
 * TransferEventConsumerTest (Mockito 单元测试)
 *
 * handleTransferEvent_shouldProcessWithoutDuplicate — 测试转账事件处理
 * handleTransferEvent_shouldSkipWhenAlreadyProcessed — 测试已处理的转账事件跳过
 * handleTransferEvent_shouldMarkFailedOnBusinessException — 测试业务异常时标记失败
 */
@ExtendWith(MockitoExtension.class)
class TransferEventConsumerTest {

    @Mock
    private ProcessedTransactionService processedTransactionService;

    @Mock
    private TransferInternalService transferInternalService;

    @InjectMocks
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        // 初始化 mock 行为
    }

    @Test
    void handleTransferEvent_shouldProcessWithoutDuplicate() {
        // Given
        String transactionId = "txn-12345";
        TransferRequest request = new TransferRequest();
        request.setFromAccountNo("ACC001");
        request.setToAccountNo("ACC002");
        request.setAmount(new BigDecimal("1000"));
        request.setRequestId("req-001");

        when(processedTransactionService.isTransactionProcessed(transactionId)).thenReturn(false);
        when(processedTransactionService.markAsProcessing(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);

        // When
        accountService.handleTransferEvent(transactionId, request);

        // Then
        verify(transferInternalService).executeTransfer("ACC001", "ACC002", new BigDecimal("1000"));
        verify(processedTransactionService).markAsCompleted(transactionId);
    }

    @Test
    void handleTransferEvent_shouldSkipWhenAlreadyProcessed() {
        // Given
        String transactionId = "txn-99999";
        TransferRequest request = new TransferRequest();
        request.setFromAccountNo("ACC001");
        request.setToAccountNo("ACC002");
        request.setAmount(new BigDecimal("500"));
        request.setRequestId("req-002");

        when(processedTransactionService.isTransactionProcessed(transactionId)).thenReturn(true);

        // When
        accountService.handleTransferEvent(transactionId, request);

        // Then
        verify(transferInternalService, never()).executeTransfer(anyString(), anyString(), any());
        verify(processedTransactionService, never()).markAsCompleted(anyString());
    }

    @Test
    void handleTransferEvent_shouldMarkFailedOnBusinessException() {
        // Given
        String transactionId = "txn-fail";
        TransferRequest request = new TransferRequest();
        request.setFromAccountNo("ACC001");
        request.setToAccountNo("ACC002");
        request.setAmount(new BigDecimal("20000")); // 超过限额
        request.setRequestId("req-003");

        when(processedTransactionService.isTransactionProcessed(transactionId)).thenReturn(false);
        when(processedTransactionService.markAsProcessing(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);

        // When
        accountService.handleTransferEvent(transactionId, request);

        // Then
        verify(processedTransactionService).markAsFailed(eq(transactionId), anyString());
    }
}
