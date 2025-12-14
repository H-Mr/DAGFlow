package cn.hjw.dev.dagflow;

public interface ExecutableGraph<T,C,R> {

    /**
     * 图的执行入口
     * @param request
     * @param context
     * @return
     */
    R apply(T request, C context) throws Exception;
}
