package com.jinjing.banking.modules.transaction.repository;

import com.jinjing.banking.modules.transaction.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    // 校验该事务是否已经在发送队列中，实现 Controller 层的初步幂等
    // 硬核点：根据客户端请求 ID 查重，利用数据库的唯一约束保证幂等性
    boolean existsByClientRequestId(String clientRequestId);

    /**
     * 业内真正的高并发分发方案：FOR UPDATE SKIP LOCKED
     * 确保多个后端实例同时轮询时，能够自动跳过已被锁定的行，实现无损、无锁冲突的并发抓取。
     */
    @Query(value = "SELECT * FROM outbox_event ORDER BY id ASC FOR UPDATE SKIP LOCKED", 
           nativeQuery = true)
    List<OutboxEvent> fetchPendingEvents(Pageable pageable);
}