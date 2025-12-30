package cn.hjw.dev.dagflow;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.engine.DAGEngine;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class Phase2CoreTest {

    private final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    /**
     * 核心测试 1: 验证全异步并发能力 (解决短板效应)
     * 场景:
     * Node A: 耗时 50ms (快)
     * Node B: 耗时 2000ms (慢)
     * Node C: 依赖 Node A (应该很快开始)
     * Node D: 依赖 Node B (必须等 B)
     *
     * 预期:
     * Phase 1 (旧): Node C 必须等 Node B 跑完 (Level 1 Barrier)，耗时 > 2000ms 才能开始。
     * Phase 2 (新): Node C 应该在 ~50ms 后立即开始。
     */
    @Test
    public void testAsyncPerformance_StragglerProblem() throws Exception {
        // 记录各节点结束时间
        long[] endTimes = new long[4]; // 0:A, 1:B, 2:C, 3:D
        long start = System.currentTimeMillis();

        // Node A (50ms)
        DAGNodeProcessor<String> nodeA = (req, input) -> {
            Thread.sleep(50);
            log.info("Node A finished");
            endTimes[0] = System.currentTimeMillis() - start;
            return "A";
        };

        // Node B (1000ms) - 拖后腿的节点
        DAGNodeProcessor<String> nodeB = (req, input) -> {
            Thread.sleep(1000);
            log.info("Node B finished");
            endTimes[1] = System.currentTimeMillis() - start;
            return "B";
        };

        // Node C (Depends on A)
        DAGNodeProcessor<String> nodeC = (req, input) -> {
            // 验证它是否拿到了 A 的结果
            String resA = input.get("nodeA", String.class);
            log.info("Node C started. Input from A: {}", resA);
            endTimes[2] = System.currentTimeMillis() - start;
            return "C";
        };

        // Node D (Depends on B)
        DAGNodeProcessor<String> nodeD = (req, input) -> {
            input.get("nodeB");
            log.info("Node D started.");
            endTimes[3] = System.currentTimeMillis() - start;
            return "D";
        };

        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB)
                .addNode("nodeC", nodeC)
                .addNode("nodeD", nodeD);

        // A -> C (快链路)
        config.addRoute("nodeA", "nodeC");
        // B -> D (慢链路)
        config.addRoute("nodeB", "nodeD");

        config.setTerminalStrategy((req, res) -> "OK");

        DAGEngine<String, String> engine = new DAGEngine<>(config);
        engine.apply("req");

        log.info("Time Stats: A={}, B={}, C={}, D={}", endTimes[0], endTimes[1], endTimes[2], endTimes[3]);

        // --- 关键断言 ---

        // 1. 验证 C 是不是在 A 结束后立刻执行的 (允许少量调度误差，例如 100ms)
        // 如果是 Phase 1，C 的结束时间会在 B 之后 (即 > 1000ms)。
        // 在 Phase 2，C 应该在 100ms 以内完成 (50ms A + 调度)。
        Assertions.assertTrue(endTimes[2] < 500, "Node C 应该不受 Node B 影响，快速完成。实际耗时: " + endTimes[2]);

        // 2. 验证 D 确实等待了 B
        Assertions.assertTrue(endTimes[3] >= 1000, "Node D 必须等待 Node B");
    }

    /**
     * 核心测试 2: 验证数据流传递准确性 (UpstreamInput)
     * 场景: 菱形依赖 A -> (B, C) -> D
     * 验证 D 能否正确获取 B 和 C 的不同类型的返回值
     */
    @Test
    public void testDataFlowAndTypeSafety() throws Exception {
        // A: 返回 String "Base"
        DAGNodeProcessor<String> nodeA = (req, input) -> "Base";

        // B: 依赖 A，返回 Integer (Length of A)
        DAGNodeProcessor<String> nodeB = (req, input) -> {
            String valA = input.get("nodeA", String.class);
            return valA.length(); // 4
        };

        // C: 依赖 A，返回 String (A + "Copy")
        DAGNodeProcessor<String> nodeC = (req, input) -> {
            String valA = input.get("nodeA", String.class);
            return valA + "Copy"; // "BaseCopy"
        };

        // D: 依赖 B, C，聚合结果
        DAGNodeProcessor<String> nodeD = (req, input) -> {
            Integer valB = input.get("nodeB", Integer.class);
            String valC = input.get("nodeC", String.class);
            return valC + ":" + valB; // "BaseCopy:4"
        };

        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB)
                .addNode("nodeC", nodeC)
                .addNode("nodeD", nodeD);

        config.addRoute("nodeA", "nodeB")
                .addRoute("nodeA", "nodeC")
                .addRoute("nodeB", "nodeD")
                .addRoute("nodeC", "nodeD");

        config.setTerminalStrategy((req, results) -> (String) results.get("nodeD"));

        DAGEngine<String, String> engine = new DAGEngine<>(config);
        String result = engine.apply("req");

        Assertions.assertEquals("BaseCopy:4", result);
    }
}