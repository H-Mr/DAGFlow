package cn.hjw.dev.dagflow.node;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.gov.NodeGovernance;
import cn.hjw.dev.dagflow.engine.DAGEngine;
import cn.hjw.dev.dagflow.hook.FallbackStrategy;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 2 版本治理能力测试
 * 验证：重试、超时、降级在全异步递归架构下是否依然有效
 */
@Slf4j
public class GovernanceTest {

    private final ExecutorService threadPool = Executors.newFixedThreadPool(4);

    /**
     * 场景 1: 测试重试机制
     * 验证 ResilientNodeProcessor 是否在递归 Future 链中正确工作
     */
    @Test
    public void testRetrySuccess() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // 1. 定义不稳定的节点 (泛型简化：只定义 Request 类型)
        DAGNodeProcessor<String> unstableNode = (req, input) -> {
            int current = callCount.incrementAndGet();
            log.info("节点执行第 {} 次调用...", current);
            if (current <= 2) {
                throw new RuntimeException("模拟网络波动异常");
            }
            return "SuccessData";
        };

        NodeGovernance governance = NodeGovernance.builder()
                .maxRetries(3)
                .retryBackoff(50)
                .build();

        // 2. 配置 (泛型简化为 <Request, Response>)
        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", unstableNode, governance);

        // 3. 终结策略：从结果集中获取 nodeA 的结果
        config.setTerminalStrategy((req, results) -> (String) results.get("nodeA"));

        // 4. 执行
        DAGEngine<String, String> engine = new DAGEngine<>(config);
        String result = engine.apply("req");

        // 5. 验证
        Assertions.assertEquals("SuccessData", result);
        Assertions.assertEquals(3, callCount.get());
    }

    /**
     * 场景 2: 测试重试耗尽后降级
     * 验证 FallbackStrategy 是否能接管异常
     */
    @Test
    public void testRetryExhaustedWithFallback() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        DAGNodeProcessor<String> brokenNode = (req, input) -> {
            callCount.incrementAndGet();
            throw new IllegalStateException("DB连接失败");
        };

        // 降级策略适配 Phase 2 接口
        FallbackStrategy<String> mockFallback = (req, input, ex) -> {
            log.error("捕获异常: {}, 执行降级...", ex.getMessage());
            return "MockData";
        };

        NodeGovernance governance = NodeGovernance.builder()
                .maxRetries(2)
                .retryBackoff(10)
                .fallbackStrategy(mockFallback)
                .build();

        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeB", brokenNode, governance);
        config.setTerminalStrategy((req, results) -> (String) results.get("nodeB"));

        DAGEngine<String, String> engine = new DAGEngine<>(config);
        String result = engine.apply("req");

        Assertions.assertEquals("MockData", result);
        Assertions.assertEquals(3, callCount.get());
    }

    /**
     * 场景 3: 测试节点级超时 (Timeout)
     * 验证递归构建的 Future 链中的 orTimeout 是否生效
     */
    @Test
    public void testNodeTimeout() throws Exception {
        DAGNodeProcessor<String> slowNode = (req, input) -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return "Interrupted";
            }
            return "SlowData";
        };

        FallbackStrategy<String> timeoutFallback = (req, input, ex) -> {
            log.warn("触发超时降级: {}", ex.getClass().getSimpleName());
            return "TimeoutFallback";
        };

        NodeGovernance governance = NodeGovernance.builder()
                .timeout(200)
                .timeUnit(TimeUnit.MILLISECONDS)
                .fallbackStrategy(timeoutFallback)
                .build();

        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeC", slowNode, governance);
        config.setTerminalStrategy((req, results) -> (String) results.get("nodeC"));

        DAGEngine<String, String> engine = new DAGEngine<>(config);

        long start = System.currentTimeMillis();
        String result = engine.apply("req");
        long cost = System.currentTimeMillis() - start;

        log.info("执行耗时: {} ms", cost);
        Assertions.assertEquals("TimeoutFallback", result);
        Assertions.assertTrue(cost < 800);
    }
}