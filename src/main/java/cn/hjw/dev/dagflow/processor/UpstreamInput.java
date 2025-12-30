package cn.hjw.dev.dagflow.processor;

import java.util.Optional;

/**
 * 上游数据访问器
 * 提供给节点使用的只读视图，用于获取依赖节点的执行结果
 */
public interface UpstreamInput {

    /**
     * 获取指定节点的执行结果
     * @param nodeId 上游节点ID
     * @param type   期望的结果类型
     * @return 结果对象
     * @throws ClassCastException 如果类型不匹配
     * @throws IllegalArgumentException 如果节点不存在或无结果
     */
    <T> T get(String nodeId, Class<T> type);

    /**
     * 获取指定节点的执行结果 (无需显式传参，依靠泛型推断)
     * @param nodeId 上游节点ID
     * @return 结果对象
     */
    <T> T get(String nodeId);
}