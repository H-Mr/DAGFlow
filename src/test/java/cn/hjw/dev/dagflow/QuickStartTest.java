package cn.hjw.dev.dagflow;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.engine.DAGEngine;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAGFlow 快速入门测试案例
 * 演示场景：并行计算 (ValueA + ValueB)
 */
@Slf4j
public class QuickStartTest {

    // 1. 定义一个简单的结果包装类，用于节点返回数据
    // 因为 UpdateStrategy 接收不到 NodeId，所以我们需要在返回值里带上 "我是谁"
    @Data
    @AllArgsConstructor
    static class NodeResult {
        private String key;
        private Integer value;
    }


    @Test
    public void testSimpleMathDAG() throws Exception {
        // --- 准备工作 ---
        // 创建线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(4);

        // 初始化配置：
        // T(请求参数): String (模拟请求ID)
        // C(上下文): Map<String, Integer> (用于存储中间计算结果)
        // V(节点返回值): NodeResult (节点计算结果)
        // R(最终返回值): Integer (整个图的执行结果)
        GraphConfig<String,
                Map<String, Integer>,
                NodeResult,
                Integer> config = new GraphConfig<>(threadPool);

        // --- 第一步：定义节点逻辑 ---

        // 节点 A：模拟耗时操作，返回 10
        DAGNodeProcessor<String,
                Map<String, Integer>,
                NodeResult> nodeA = (req, ctx) -> {
            log.info("节点 A 开始执行...");
            Thread.sleep(100); // 模拟并行耗时
            return new NodeResult("A", 10);
        };

        // 节点 B：模拟耗时操作，返回 20
        DAGNodeProcessor<String,
                Map<String, Integer>,
                NodeResult> nodeB = (req, ctx) -> {
            log.info("节点 B 开始执行...");
            Thread.sleep(100);
            return new NodeResult("B", 20);
        };

        // 节点 C (Sum)：依赖 A 和 B，计算求和
        DAGNodeProcessor<String,
                Map<String, Integer>,
                NodeResult> nodeC = (req, ctx) -> {
            log.info("节点 C 开始执行...");
            // 从上下文中获取 A 和 B 的结果
            Integer valA = ctx.get("A");
            Integer valB = ctx.get("B");
            return new NodeResult("C", valA + valB);
        };

        // --- 第二步：组装图 (注册节点 & 路由) ---

        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB)
                .addNode("nodeC", nodeC);

        // 建立依赖关系：A -> C, B -> C
        // 意味着 C 必须等 A 和 B 都执行完才能执行
        config.addRoute("nodeA", "nodeC");
        config.addRoute("nodeB", "nodeC");

        // --- 第三步：配置全局策略 ---

        // 3.1 上下文更新策略：节点执行完后，如何把结果写回 Context？
        config.setUpdateStrategy((req, ctx, result) -> {
            log.info("更新上下文: key={}, value={}", result.getKey(), result.getValue());
            ctx.put(result.getKey(), result.getValue());
        });

        // 3.2 终结策略：所有节点执行完后，返回什么给用户？
        config.setTerminalStrategy((req, ctx) -> {
            log.info("执行完毕，最终上下文: {}", ctx);
            return ctx.get("C"); // 返回节点 C 的计算结果
        });

        // --- 第四步：启动引擎 ---

        DAGEngine<String,
                Map<String, Integer>,
                NodeResult,
                Integer> engine = new DAGEngine<>(config);

        // 创建本次DAG运行的上下文；将配置与运行隔离，保证线程安全

        Map<String, Integer> context = new ConcurrentHashMap<>();

        long start = System.currentTimeMillis();
        Integer result = engine.apply("Request-001", context);
        long end = System.currentTimeMillis();

        log.info("DAG 执行总耗时: {} ms, 结果: {}", (end - start), result);

        // --- 第五步：验证结果 ---
        Assertions.assertEquals(30, result);
        // 验证 A 和 B 确实写入了上下文
        Assertions.assertEquals(10, context.get("A"));
        Assertions.assertEquals(20, context.get("B"));
    }

    public static void main(String[] args) throws Exception {

        // 节点 A: 模拟耗时操作，返回 key="A", value=10
        DAGNodeProcessor<String, Map<String, Object>, NodeResult> nodeA = (req, ctx) -> {
            Thread.sleep(100);
            System.out.println("节点 A 执行完毕,花费 100 ms");
            return new NodeResult("A", 10);
        };

        // 节点 B: 模拟耗时操作，返回 key="B", value=20
        DAGNodeProcessor<String, Map<String, Object>, NodeResult> nodeB = (req, ctx) -> {
            Thread.sleep(100);
            System.out.println("节点 B 执行完毕,花费 100 ms");
            return new NodeResult("B", 20);
        };

        // 节点 Sum: 依赖 A, B，计算 (A+B)*2
        DAGNodeProcessor<String, Map<String, Object>, NodeResult> nodeSum = (req, ctx) -> {
            int a = (int) ctx.get("A");
            int b = (int) ctx.get("B");
            return new NodeResult("C", (a + b) * 2);
        };


// 1. 初始化线程池与配置
        ExecutorService threadPool = Executors.newFixedThreadPool(4);
        // 泛型定义: <Request, Context, NodeResult, FinalResult>
        GraphConfig<String, Map<String, Object>, NodeResult, Integer> config = new GraphConfig<>(threadPool);

        // 2. 注册节点
        config.addNode("nodeA", nodeA)
                .addNode("nodeB", nodeB)
                .addNode("nodeC", nodeSum);

        // 3. 建立依赖关系：A -> C, B -> C
        config.addRoute("nodeA", "nodeC");
        config.addRoute("nodeB", "nodeC");

        // 4. 配置全局策略

        // 4.1 上下文更新策略：将 NodeResult 解析并写入 Context
        config.setUpdateStrategy((req, ctx, result) -> {
            System.out.println("更新上下文: key=" + result.getKey() + ", value=" + result.getValue());
            ctx.put(result.getKey(), result.getValue());
        });

        // 4.2 终结策略：从 Context 中获取最终结果
        config.setTerminalStrategy((req, ctx) -> {
            return (Integer) ctx.get("C");
        });

        // 5. 启动引擎
        DAGEngine<String, Map<String, Object>, NodeResult, Integer> engine = new DAGEngine<>(config);

        // 6. 执行
        System.out.println("计算（A+B）* 2");
        Map<String, Object> context = new ConcurrentHashMap<>();
        long start = System.currentTimeMillis();
        Integer result = engine.apply("Request-001", context);
        long end = System.currentTimeMillis();
        threadPool.shutdown();
        System.out.println("DAG 执行总耗时: "+  (end - start) + " ms");

        System.out.println("最终结果: " + result); // Output: 60
    }
}