package cn.hjw.dev.dagflow.gov;

import cn.hjw.dev.dagflow.hook.FallbackStrategy;
import lombok.Builder;
import lombok.Getter;
import java.util.concurrent.TimeUnit;

/**
 * 治理方案：
 * 1. 个性化每个节点的治理
 * 2. 默认全局治理
 * 3. 框架自动兜底
 */
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
    private int maxRetries = 0; // 最大重试次数

    @Builder.Default
    private long retryBackoff = 0; // 毫秒,每次重试的退避时间

    // --- 降级配置 ---
    private FallbackStrategy<?> fallbackStrategy;
}