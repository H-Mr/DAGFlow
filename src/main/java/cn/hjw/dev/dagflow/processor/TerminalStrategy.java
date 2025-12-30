package cn.hjw.dev.dagflow.processor;

import java.util.Map;

/**
 * 终结策略：决定最终返回值
 * @param <T> 请求类型
 * @param <R> 最终返回类型
 */
@FunctionalInterface
public interface TerminalStrategy<T, R> {

    /**
     * 流程结束时被调用
     * @param request 请求参数
     * @param executionResults 所有节点的执行结果 (Key: NodeId, Value: Result)
     * @return 最终结果
     */
    R terminate(T request, Map<String, Object> executionResults) throws Exception;
}