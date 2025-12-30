package cn.hjw.dev.dagflow.config;

import cn.hjw.dev.dagflow.hook.FallbackStrategy;
import lombok.Builder;
import lombok.Getter;
import java.util.concurrent.TimeUnit;

@Getter
@Builder
public class NodeGovernance {

    // --- 超时配置 ---
    @Builder.Default
    private long timeout = 0;

    @Builder.Default
    private TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    // --- 重试配置 ---
    @Builder.Default
    private int maxRetries = 0;

    @Builder.Default
    private long retryBackoff = 0;

    // --- 降级配置 ---
    private FallbackStrategy<?> fallbackStrategy;
}