package cn.hjw.dev.dagflow.config;

import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.processor.TerminalStrategy;
import cn.hjw.dev.dagflow.processor.UpdateStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@AllArgsConstructor
@Builder
@Data
public class GraphConfig<T,C,V,R> {

    private Map<String,List<String>> routeTable; // DAG邻接表

    private Map<String, DAGNodeProcessor<T,C,V>> nodeStrategyMap; // 节点处理器表

    private TerminalStrategy<T,C,R> terminalStrategy; // 获取图执行结果的策略函数

    private UpdateStrategy<T,C,V> updateStrategy; // 图更新策略函数

    private ExecutorService threadPool; // 图执行线程池

    public GraphConfig() {
        routeTable = new HashMap<>();
        nodeStrategyMap = new HashMap<>();
    }

    public GraphConfig(ExecutorService threadPool) {
        this();
        this.threadPool = threadPool;
    }

    /**
     * 添加路由规则 (支持多次调用添加多个目标)
     * 要理解 putIfAbsent、computeIfAbsent、computeIfPresent 的区别，
     * 核心要抓住触发条件、入参类型、返回值和内存 / 性能特性—— 这三个都是 Java 8+ 为 Map 新增的 “条件性操作” 方法，但适用场景和行为完全不同。
     */
    public GraphConfig<T,C,V,R> addRoute(String nodeId,String nextId) {

        routeTable.computeIfAbsent(nodeId, k -> new ArrayList<>())
                .add(nextId);
        return this;
    }

    public GraphConfig<T,C,V,R> addNode(String nodeId, DAGNodeProcessor<T,C,V> DAGNodeProcessor) {
        nodeStrategyMap.computeIfAbsent(nodeId, k -> DAGNodeProcessor);
        return this;
    }

}
