package cn.hjw.dev.dagflow.compile;

import cn.hjw.dev.dagflow.condition.EdgeCondition;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.gov.NodeGovernance;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.processor.ResilientNodeProcessor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.*;


public class DAGCompiler {

    /**
     * 编译图配置为执行计划
     * @param config 图配置
     * @param <T> 全局请求参数类型
     * @param <R> 节点结果类型
     * @return 执行计划
     */
    public static <T, R> ExecutionPlan<T> compile(GraphConfig<T, R> config) {
        Map<String, List<String>> routeTable = config.getRouteTable(); // 获取DAG的路由表
        Set<String> allNodes = config.getNodeStrategyMap().keySet(); // 获取所有注册的节点ID

        // 1. 统计每个节点的入度 判断是否是合法的DAG
        Map<String, Integer> inDegree = new HashMap<>(); // 入度表
        allNodes.forEach(v -> inDegree.put(v, 0)); // 初始化入度为0,防止有一些孤立节点没有被统计到
        routeTable.forEach((par, children) -> {
            if(!allNodes.contains(par) || children == null) return; // 忽略未注册的节点
            children.forEach(u -> {
                if (!allNodes.contains(u)) return; // 忽略未注册的节点
                inDegree.merge(u, 1, Integer::sum);
            });
        });
        // 拓扑排序校验环
        int count = 0;
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((k, v) -> {
            if (v == 0) queue.offer(k);
        });
        while (!queue.isEmpty()) {
            String node = queue.poll();
            count++;
            List<String> children = routeTable.get(node);
            if (children == null)
                continue;
            for (String child : children) {
                int in = inDegree.merge(child, -1, Integer::sum); // 入度减一
                if (in == 0)
                    queue.offer(child);
            }
        }

        if (count != allNodes.size()) {
            throw new IllegalStateException("DAG cycle detected or disconnected nodes found!");
        }

        // 2. 构建反向依赖表
        Map<String, List<String>> nodeParentsMap = new HashMap<>();
        routeTable.forEach((par, children) -> {
            if(!allNodes.contains(par) || children == null) return; // 忽略未注册的节点
            children.forEach(u -> {
                if (!allNodes.contains(u)) return; // 忽略未注册的节点
                nodeParentsMap.computeIfAbsent(u, k -> new ArrayList<>()).add(par);
            });
        });


        // 2. 将节点的治理策略放到对应的处理器上
        Map<String, DAGNodeProcessor<T>> wrappedProcessors = new HashMap<>();
        config.getNodeStrategyMap().forEach((id, processor) -> {
            NodeGovernance gov = config.getGovernance(id);
            if (gov != null && gov.getMaxRetries() > 0) {
                wrappedProcessors.put(id, new ResilientNodeProcessor<>(id, processor, gov));
            } else {
                wrappedProcessors.put(id, processor);
            }
        });

        return new ExecutionPlan<>(
                allNodes,
                nodeParentsMap,
                wrappedProcessors,
                config.getGovernanceMap(),
                config.getEdgeConditionMap()
        );
    }

    @Getter
    @RequiredArgsConstructor
    public static class ExecutionPlan<T> {

        // 所有节点ID集合
        private final Set<String> allNodes;

        // 节点依赖关系: Key=NodeId, Value=List of Parent NodeIds
        // 注意：GraphConfig 存的是 A->B (A是B的父)，这里我们要把它转成 B->[A] (方便B找爸爸)
        private final Map<String, List<String>> nodeParentsMap;

        // 经过 Resilient 包装后的处理器
        private final Map<String, DAGNodeProcessor<T>> processors;

        // 治理配置
        private final Map<String, NodeGovernance> governances;

        // 节点执行条件
        private final Map<String, EdgeCondition<T>> edgeConditions;
    }

}
