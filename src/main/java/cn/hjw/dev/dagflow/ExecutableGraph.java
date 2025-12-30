package cn.hjw.dev.dagflow;

public interface ExecutableGraph<T, R> {

    /**
     * 执行图
     * @param request 请求参数
     * @return 执行结果
     */
    R apply(T request) throws Exception;
}