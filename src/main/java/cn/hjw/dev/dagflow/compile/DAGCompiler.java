package cn.hjw.dev.dagflow.compile;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.config.NodeGovernance;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.processor.NodeCondition;
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
        Map<String, List<String>> routeTable = config.getRouteTable(); // Parent -> Children
        Set<String> allNodes = config.getNodeStrategyMap().keySet();

        // 1. 环检测 (基于 Kahn 算法) & 构建反向依赖表 (Child -> Parents)
        Map<String, List<String>> nodeParentsMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        allNodes.forEach(v -> {
            nodeParentsMap.put(v, new ArrayList<>());
            inDegree.put(v, 0);
        });

        routeTable.forEach((node, children) -> {
            if(!allNodes.contains(node)) return; // 忽略未注册的节点
            if (children == null) return;
            children.forEach(u -> {
                if (!allNodes.contains(u)) return; // 忽略未注册的节点
                nodeParentsMap.get(u).add(node); // 填充反向依赖
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
            if (children != null) {
                for (String child : children) {
                    inDegree.put(child, inDegree.get(child) - 1);
                    if (inDegree.get(child) == 0) {
                        queue.offer(child);
                    }
                }
            }
        }

        if (count != allNodes.size()) {
            throw new IllegalStateException("DAG cycle detected or disconnected nodes found!");
        }

        // 2. 包装处理器 (Governance Decorator)
        Map<String, DAGNodeProcessor<T>> wrappedProcessors = new HashMap<>();
        config.getNodeStrategyMap().forEach((id, processor) -> {
            NodeGovernance governance = config.getGovernance(id);
            if (governance != null && governance.getMaxRetries() > 0) {
                wrappedProcessors.put(id, new ResilientNodeProcessor<>(id, processor, governance));
            } else {
                wrappedProcessors.put(id, processor);
            }
        });

        return new ExecutionPlan<>(
                allNodes,
                nodeParentsMap,
                wrappedProcessors,
                config.getGovernanceMap(),
                config.getNodeConditionMap()
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
        private final Map<String, NodeCondition<T>> nodeConditions;
    }

}
