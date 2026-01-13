package cn.hjw.dev.dagflow.condition;

import cn.hjw.dev.dagflow.ctx.UpstreamInput;

/**
 * 边条件 (Edge Condition)
// * 定义：连接 Source 节点和 Target 节点的“阀门”。
 * 作用：在 Source 执行完后求值。如果为 false，则 Target 节点及其下游自动被跳过 (SKIPPED)。
 *
 * @param <T> 全局请求类型
 */
@FunctionalInterface
public interface EdgeCondition<T> {

    /**
     * 评估边是否连通
     * @param request 全局请求
     * @param parentInput 上游节点的执行结果
     * @return true: 通路; false: 断路
     */
    boolean evaluate(T request, UpstreamInput parentInput);
}
