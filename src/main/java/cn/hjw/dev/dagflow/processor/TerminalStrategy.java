package cn.hjw.dev.dagflow.processor;

/**
 * 终止态处理策略, 用于处理DAG执行完毕后的最终返回值
 * @param <T>
 * @param <C>
 * @param <R>
 */
@FunctionalInterface
public interface TerminalStrategy<T,C,R> {

    /**
     * @param requestParam 请求参数
     * @param context 最终态的上下文
     * @return 最终给调用方的返回值
     */
    R terminate(T requestParam,C context) throws Exception;
}
