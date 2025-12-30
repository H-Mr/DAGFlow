package cn.hjw.dev.dagflow.config;

import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.processor.NodeCondition;
import cn.hjw.dev.dagflow.processor.TerminalStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@AllArgsConstructor
@Builder
@Data
public class GraphConfig<T, R> {

    // 邻接表 (Key: Parent, Value: Children) -> 其实在Compiler中我们更需要 Parent List
    // 为了方便，这里维持原样，或者可以存储依赖关系。
    // 建议：存储 Map<String, List<String>> dependencies，Key 是 Node，Value 是它的下游
    private Map<String, List<String>> routeTable;

    // 节点逻辑表
    private Map<String, DAGNodeProcessor<T>> nodeStrategyMap;

    // 治理配置表
    private Map<String, NodeGovernance> governanceMap;

    // 节点条件表
    private Map<String, NodeCondition<T>> nodeConditionMap;

    // 终结策略
    private TerminalStrategy<T, R> terminalStrategy;

    // 线程池
    private ExecutorService threadPool;

    // 全局超时
    @Getter
    private Long globalTimeoutMillis;

    public GraphConfig() {
        routeTable = new HashMap<>();
        nodeStrategyMap = new HashMap<>();
        governanceMap = new HashMap<>();
        nodeConditionMap = new HashMap<>();
    }

    public GraphConfig(ExecutorService threadPool) {
        this();
        this.threadPool = threadPool;
    }

    /**
     * 添加依赖: fromNode -> toNode
     * 意味着 toNode 依赖 fromNode
     */
    public GraphConfig<T, R> addRoute(String fromNode, String toNode) {
        routeTable.computeIfAbsent(fromNode, k -> new ArrayList<>()).add(toNode);
        return this;
    }

    public GraphConfig<T, R> addNode(String nodeId, DAGNodeProcessor<T> processor) {
        return addNode(nodeId, processor, null);
    }

    public GraphConfig<T, R> addNode(String nodeId, DAGNodeProcessor<T> processor, NodeGovernance governance) {
        this.nodeStrategyMap.put(nodeId, processor);
        if (governance != null) {
            this.governanceMap.put(nodeId, governance);
        }
        return this;
    }

    //  全参数注册 (含条件)
    public GraphConfig<T, R> addNode(String nodeId, DAGNodeProcessor<T> processor,
                                     NodeGovernance governance, NodeCondition<T> condition) {
        this.nodeStrategyMap.put(nodeId, processor);
        if (governance != null) {
            this.governanceMap.put(nodeId, governance);
        }
        if (condition != null) {
            this.nodeConditionMap.put(nodeId, condition);
        }
        return this;
    }

    // 获取条件
    public NodeCondition<T> getNodeCondition(String nodeId) {
        return nodeConditionMap.getOrDefault(nodeId,null);
    }

    public NodeGovernance getGovernance(String nodeId) {
        return governanceMap.getOrDefault(nodeId,null);
    }
}