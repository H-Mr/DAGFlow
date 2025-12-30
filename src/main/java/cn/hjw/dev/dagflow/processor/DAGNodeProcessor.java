package cn.hjw.dev.dagflow.processor;

/**
 * DAG节点处理器接口
 * @param <T> 全局请求参数类型 (Request)
 */
@FunctionalInterface
public interface DAGNodeProcessor<T> {

    /**
     * 执行节点逻辑
     * @param request 全局请求参数
     * @param input   上游输入访问器
     * @return 节点计算结果 (由框架托管)
     * @throws Exception 执行异常
     */
    Object process(T request, UpstreamInput input) throws Exception;
}