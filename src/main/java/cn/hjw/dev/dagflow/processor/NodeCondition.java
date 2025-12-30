package cn.hjw.dev.dagflow.processor;

/**
 * 节点执行条件 (守卫)
 * 用于判断节点是否应该执行
 * @param <T> 请求类型
 */
@FunctionalInterface
public interface NodeCondition<T> {

    /**
     * 判断是否满足执行条件
     * @param request 原始请求
     * @param input 上游数据访问器
     * @return true: 执行节点; false: 跳过节点(SKIP)
     */
    boolean evaluate(T request, UpstreamInput input);
}