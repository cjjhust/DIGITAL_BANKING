package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.annotation.TheBestOne;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 演示使用自定义注解
 */
@Service
@Slf4j
public class BestOneExampleService {

    @TheBestOne(value = "银行核心转账模块")
    public void someVipLogic() {
        log.info("执行 VIP 核心业务逻辑中...");
    }
}
