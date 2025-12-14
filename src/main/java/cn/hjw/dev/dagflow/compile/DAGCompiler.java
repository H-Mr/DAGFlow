package cn.hjw.dev.dagflow.compile;

import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;

import java.util.*;
public class DAGCompiler {
    /**
     * 将节点表和依赖关系编译成层级计划
     * 算法：最长路径分层 (Longest Path Layering)
     * 规则：Level(Node) = Max(Level(Predecessors)) + 1
     */
    public static <T,C,V,R> ExecutionPlan<T,C,V> compile(GraphConfig<T,C,V,R> graphConfig) { // Key: Parent, Value: Children

        // 获取依赖关系图
        Map<String, List<String>> routeTable = graphConfig.getRouteTable();

        // 获取节点处理器表
        Map<String, DAGNodeProcessor<T,C, V>> processors = graphConfig.getNodeStrategyMap();

        // 计算入度表
        Map<String, Integer> inDegree = generateInDegreeMap(routeTable,processors.keySet());


        // 2. 计算每个节点的层级 (Level)
        Map<String, Integer> nodeLevels = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>(); // 拓扑排序队列

        // Level 0 节点入队
        inDegree.forEach((node, deg) -> {
            if (deg == 0) {
                queue.offer(node);
                nodeLevels.put(node, 0); // 所有入度为0的节点层级为0
            }
        });
        int cnt = 0;
        int maxLevel = 0;
        while (!queue.isEmpty()) {
          String currentNode = queue.poll();
          cnt++;
          int currentLevel = nodeLevels.get(currentNode);
          maxLevel = Math.max(currentLevel,maxLevel);
          List<String> children = routeTable.computeIfAbsent(currentNode, k ->Collections.emptyList());
          for (String child : children) {
              // 更新子节点的层级
              nodeLevels.put(child, Math.max(nodeLevels.getOrDefault(child, 0), currentLevel + 1));
              // 入度减1
              inDegree.put(child, inDegree.get(child) - 1);
              // 如果入度为0，加入队列,确定层级
              if (inDegree.get(child) == 0) {
                  queue.offer(child);
              }
          }
        }
        if (cnt != inDegree.size()) {
            throw new IllegalStateException("DAG contains a cycle, topological sort not possible.");
        }

        // 3. 将 Map<Node, Level> 转换为 List<List<CompiledNode>>
        List<List<CompiledNode<T,C, V>>> layers = new ArrayList<>();
        for (int i = 0; i <= maxLevel; i++)
            layers.add(new ArrayList<>());
        // 将每个层级的结点归位到各自的层级集合中
        nodeLevels.forEach((nodeId, level) ->
            layers.get(level).add(new CompiledNode<>(nodeId, processors.get(nodeId)))
        );
        return new ExecutionPlan<>(layers);
    }

    private static Map<String,Integer> generateInDegreeMap(Map<String, List<String>> routeTable,Set<String> allNodes) {
        Map<String, Integer> inDegree = new HashMap<>();
        // 初始化所有节点的入度为0
        allNodes.forEach(node -> inDegree.put(node, 0));
        // 计算每个节点的入度
        for (List<String> children : routeTable.values()) {
            if (children != null) {
                children.forEach(node -> inDegree.merge(node, 1, Integer::sum)); // 入度加1
            }
        }
        return inDegree;
    }

}
