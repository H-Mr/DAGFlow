package cn.hjw.dev.dagflow.config;

import cn.hjw.dev.dagflow.condition.EdgeCondition;
import cn.hjw.dev.dagflow.exception.DAGRuntimeException;
import cn.hjw.dev.dagflow.gov.NodeGovernance;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.hook.TerminalStrategy;
import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
@Builder
@Data
public class GraphConfig<T, R> {

    /**
     * 路由表: fromNode -> List<toNode>
     */
    private Map<String, List<String>> routeTable;

    /**
     * 节点处理器表: nodeId -> processor
     */
    private Map<String, DAGNodeProcessor<T>> nodeStrategyMap;

    /**
     * 节点治理表: nodeId -> governance
     */
    private Map<String, NodeGovernance> governanceMap;

    /**
     *条件表: (Key: "from->to")，表示边的条件
     */
    private Map<String, EdgeCondition<T>> edgeConditionMap;

    /**
     *  Level 2: 全局默认治理
     */
    @Setter
    @Getter
    private NodeGovernance defaultNodeGovernance;

    /**
     * 钩子函数: 流程终止时调用
     */
    private TerminalStrategy<T, R> terminalStrategy;

    /**
     * 配置DAG执行器使用的线程池
     */
    private ExecutorService threadPool;

    /**
     * 整个图的执行超时时间 (毫秒)
     */
    @Getter
    @Setter
    private Long globalTimeoutMillis;


    private GraphConfig() {
        routeTable = new HashMap<>();
        nodeStrategyMap = new HashMap<>();
        governanceMap = new HashMap<>();
        edgeConditionMap = new HashMap<>();
        defaultNodeGovernance = NodeGovernance.builder()
                .timeout(30000) // 默认30秒超时
                .timeUnit(TimeUnit.MILLISECONDS)
                .maxRetries(0) // 默认不重试
                .retryBackoff(0) // 不重试则不需要退避
                .fallbackStrategy((r,ctx,ex) -> {throw new DAGRuntimeException("default fallback !",ex);})// 默认不降级，直接返回null
                .build();
    }

    /**
     * 强制传入一个DAG执行的线程池
     * @param threadPool
     */
    public GraphConfig(ExecutorService threadPool) {
        this(); // 调用无参构造器初始化各个Map
        this.threadPool = threadPool; // 设置线程池

    }


    /**
     * 不设置边条件，默认总是连通
     * @param from
     * @param to
     * @return
     */
    public GraphConfig<T, R> addRoute(String from, String to) {
        return addRoute(from, to, (r,ctx) -> true);
    }

    public GraphConfig<T, R> addRoute(String from, String to, EdgeCondition<T> condition) {
        routeTable.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        if (condition != null) {
            edgeConditionMap.put(buildEdgeKey(from, to), condition);
        }
        return this;
    }


    public GraphConfig<T, R> addNode(String nodeId, DAGNodeProcessor<T> processor) {
        return addNode(nodeId, processor,defaultNodeGovernance);
    }

    /**
     *  重载，自定义节点的治理
     * @param nodeId
     * @param processor
     * @param governance
     * @return
     */
    public GraphConfig<T, R> addNode(String nodeId, DAGNodeProcessor<T> processor, NodeGovernance governance) {
        this.nodeStrategyMap.put(nodeId, processor);
        if (governance != null) {
            this.governanceMap.put(nodeId, governance);
        }
        return this;
    }

    /**
     * 获取节点的治理方案
     * @param nodeId
     * @return
     */
    public NodeGovernance getGovernance(String nodeId) {
        return governanceMap.getOrDefault(nodeId,null);
    }

    /**
     * 构建边的Key
     * @param from
     * @param to
     * @return
     */
    public String buildEdgeKey(String from, String to) {
        return from + "->" + to;
    }
}