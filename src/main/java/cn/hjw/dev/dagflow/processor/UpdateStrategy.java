package cn.hjw.dev.dagflow.processor;

/**
 * 上下文更新策略, 用于根据节点返回的结果更新上下文
 * @param <T> 请求参数类型
 * @param <C> 上下文类型
 * @param <V> 节点返回结果类型
 */
@FunctionalInterface
public interface UpdateStrategy<T,C,V> {

    /**
     * 根据节点返回的结果更新上下文
     * 更新上下文
     * @param requestParam 请求参数
     * @param context 上下文
     */
    void updateContext(T requestParam, C context, V result) throws Exception;
}
