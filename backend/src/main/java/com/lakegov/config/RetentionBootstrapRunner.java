package com.lakegov.config;

import com.lakegov.service.RetentionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 种子数据加载后自动重算保留截止日（幂等），
 * 保证对象目录在首次启动后即带有有效保留水位。
 */
@Component
@Order(20)
public class RetentionBootstrapRunner implements ApplicationRunner {

    private final RetentionService retention;

    public RetentionBootstrapRunner(RetentionService retention) {
        this.retention = retention;
    }

    @Override
    public void run(ApplicationArguments args) {
        retention.recalculate();
    }
}
