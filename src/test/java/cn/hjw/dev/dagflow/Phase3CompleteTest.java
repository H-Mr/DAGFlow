package cn.hjw.dev.dagflow;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.engine.DAGEngine;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class Phase3CompleteTest {

    private final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    // --- 1. 功能测试：验证“条件判断”与“级联剪枝” (Cascade Skip) ---

    /**
     * 场景：A -> B -> C
     * 条件：B 的条件返回 false (SKIP)
     * 预期：
     * 1. A 正常执行
     * 2. B 被跳过 (SKIPPED)
     * 3. C 自动被跳过 (由于依赖 B，触发级联剪枝)
     * 4. 最终结果中包含 A，不包含 B 和 C
     */
    @Test
    public void testConditionSkipAndCascade() throws Exception {
        // Node A: 总是执行
        DAGNodeProcessor<String> nodeA = (req, input) -> "DataA";

        // Node B: 总是被 SKIP
        DAGNodeProcessor<String> nodeB = (req, input) -> "DataB";
        NodeCondition<String> conditionB = (req, input) -> {
            log.info("Evaluated Condition for B: false");
            return false; // 强制跳过
        };

        // Node C: 依赖 B
        DAGNodeProcessor<String> nodeC = (req, input) -> "DataC";

        GraphConfig<String, Map<String, Object>> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB, null, conditionB) // B 绑定条件
                .addNode("nodeC", nodeC);

        config.addRoute("nodeA", "nodeB");
        config.addRoute("nodeB", "nodeC");

        // 终结策略：返回所有成功执行的结果 Map
        config.setTerminalStrategy((req, results) -> results);

        DAGEngine<String, Map<String, Object>> engine = new DAGEngine<>(config);
        Map<String, Object> results = engine.apply("req");

        log.info("Execution Results: {}", results);

        Assertions.assertTrue(results.containsKey("nodeA"), "A 应该执行成功");
        Assertions.assertFalse(results.containsKey("nodeB"), "B 应该被跳过");
        Assertions.assertFalse(results.containsKey("nodeC"), "C 应该因级联剪枝被跳过");
    }

    // --- 2. 动态逻辑测试：验证条件能读取运行时数据 ---

    /**
     * 场景：A -> B
     * 条件：B 判断 A 的结果，决定是否执行
     * 预期：根据运行时数据动态决定路径
     */
    @Test
    public void testDynamicConditionBasedOnUpstream() throws Exception {
        // Node A: 返回一个数值
        DAGNodeProcessor<Integer> nodeA = (req, input) -> req; // 返回请求传入的数值

        // Node B: 只有当 A 的结果 > 10 时才执行
        DAGNodeProcessor<Integer> nodeB = (req, input) -> "HighValue";
        NodeCondition<Integer> conditionB = (req, input) -> {
            Integer valA = input.get("nodeA", Integer.class);
            return valA > 10;
        };

        GraphConfig<Integer, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB, null, conditionB);
        config.addRoute("nodeA", "nodeB");

        config.setTerminalStrategy((req, results) -> (String) results.get("nodeB"));
        DAGEngine<Integer, String> engine = new DAGEngine<>(config);

        // Case 1: 输入 5 (应该跳过 B)
        String res1 = engine.apply(5);
        Assertions.assertNull(res1, "A(5) <= 10, B 应该跳过，返回 null");

        // Case 2: 输入 15 (应该执行 B)
        String res2 = engine.apply(15);
        Assertions.assertEquals("HighValue", res2, "A(15) > 10, B 应该执行");
    }

    // --- 3. 严格模式测试：多父节点混合 (Strict Mode) ---

    /**
     * 场景：
     * A (Run)
     * B (Skip)
     * C (Depends on A & B)
     *
     * 预期：由于 C 依赖的 B 被跳过，根据严格模式，C 也必须被跳过 (即使 A 成功了)。
     */
    @Test
    public void testStrictBranchingMode() throws Exception {
        DAGNodeProcessor<String> nodeA = (req, input) -> "A";
        DAGNodeProcessor<String> nodeB = (req, input) -> "B";
        // B 条件为 false
        NodeCondition<String> conditionB = (req, input) -> false;

        DAGNodeProcessor<String> nodeC = (req, input) -> "C";

        GraphConfig<String, Map<String, Object>> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB, null, conditionB)
                .addNode("nodeC", nodeC);

        config.addRoute("nodeA", "nodeC"); // A -> C
        config.addRoute("nodeB", "nodeC"); // B -> C

        config.setTerminalStrategy((req, results) -> results);

        DAGEngine<String, Map<String, Object>> engine = new DAGEngine<>(config);
        Map<String, Object> results = engine.apply("req");

        Assertions.assertTrue(results.containsKey("nodeA"));
        Assertions.assertFalse(results.containsKey("nodeB"));
        Assertions.assertFalse(results.containsKey("nodeC"), "因为 B 被跳过，C 必须被级联跳过");
    }

    // --- 4. 异常测试：条件求值抛异常 ---

    /**
     * 场景：条件逻辑本身报错 (例如空指针)
     * 预期：整个 DAG 应该失败 (抛出异常)，而不是被视为“条件为假”。
     * 条件错误属于代码 BUG，不能掩盖。
     */
    @Test
    public void testConditionException() {
        DAGNodeProcessor<String> nodeA = (req, input) -> "A";
        NodeCondition<String> badCondition = (req, input) -> {
            throw new RuntimeException("Condition logic error!");
        };

        GraphConfig<String, String> config = new GraphConfig<>(threadPool);
        config.addNode("nodeA", nodeA, null, badCondition);
        config.setTerminalStrategy((req, res) -> "OK");

        DAGEngine<String, String> engine = new DAGEngine<>(config);

        Assertions.assertThrows(RuntimeException.class, () -> {
            engine.apply("req");
        }, "条件抛出异常应该中断流程");
    }

    // --- 5. 性能/兼容性测试：无条件的大规模并发 ---

    /**
     * 验证 Phase 3 引入的 NodeEntry 包装和状态检查不会显著拖慢无条件节点的执行。
     */
    @Test
    public void testBackwardCompatibilityAndPerformance() throws Exception {
        int nodeCount = 100;
        GraphConfig<String, Integer> config = new GraphConfig<>(threadPool);

        // 创建 10000 个并行节点，无条件
        for (int i = 0; i < nodeCount; i++) {
            config.addNode("node" + i, (req, input) -> {
                Thread.sleep(10);
                return 1;
            });
        }

        // 汇聚节点
        config.addNode("sumNode", (req, input) -> {
            int sum = 0;
            for (int i = 0; i < nodeCount; i++) {
                sum += input.get("node" + i, Integer.class);
            }
            return sum;
        });

        // 建立依赖
        for (int i = 0; i < nodeCount; i++) {
            config.addRoute("node" + i, "sumNode");
        }

        config.setTerminalStrategy((req, results) -> (Integer) results.get("sumNode"));

        DAGEngine<String, Integer> engine = new DAGEngine<>(config);

        long start = System.currentTimeMillis();
        Integer result = engine.apply("req");
        long cost = System.currentTimeMillis() - start;

        log.info("100 nodes parallel execution cost: {} ms", cost);

        Assertions.assertEquals(100, result);
        // 在本地环境这应该极快，证明包装层开销可忽略
        Assertions.assertTrue(cost < 1000, "性能损耗应在合理范围内");
    }
}