package cn.hjw.dev.dagflow.processor;


/**
 * DAG每个节点处理策略
 * @param <T>
 * @param <C>
 * @param <R>
 */
@FunctionalInterface
public interface DAGNodeProcessor<T,C,V> {

    /**
     * DAG节点处理器
     * @param requestParam 传入流程图的参数
     * @param readonlyContext 只读上下文
     * @return
     */
    V process(T requestParam, final C readonlyContext) throws Exception;
}
