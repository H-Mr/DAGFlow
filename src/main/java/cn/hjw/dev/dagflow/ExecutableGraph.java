package cn.hjw.dev.dagflow;

/**
 * T 输入图的请求
 * R 输出图的结果
 * @param <T>
 * @param <R>
 */
public interface ExecutableGraph<T, R> {

    /**
     * 执行图
     * @param request 请求参数
     * @return 执行结果
     */
    R apply(T request) throws Exception;
}