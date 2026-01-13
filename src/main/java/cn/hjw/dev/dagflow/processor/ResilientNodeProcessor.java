package cn.hjw.dev.dagflow.processor;

import cn.hjw.dev.dagflow.ctx.UpstreamInput;
import cn.hjw.dev.dagflow.gov.NodeGovernance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 *  利用装饰器模式，为业务的DAG节点添加治理策略
 * @param <T>
 */
@Slf4j
@RequiredArgsConstructor
public class ResilientNodeProcessor<T> implements DAGNodeProcessor<T> {

    private final String nodeId;
    private final DAGNodeProcessor<T> delegate;
    private final NodeGovernance governance;

    /**
     * 执行节点逻辑，包含重试机制
     * @param request 全局请求参数
     * @param input   上游输入访问器
     * @return
     * @throws Exception
     */
    @Override
    public Object process(T request, UpstreamInput input) throws Exception {
        int maxRetries = governance != null ? governance.getMaxRetries() : 0;
        long backoff = governance != null ? governance.getRetryBackoff() : 0;

        int attempt = 0; // 当前尝试次数
        Exception lastException = null; // 记录最后一次异常

        while (attempt <= maxRetries) {
            try {
                return delegate.process(request, input);
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt <= maxRetries) {
                    log.warn("Node [{}] failed (attempt {}/{}), retrying...", nodeId, attempt, maxRetries);
                    if (backoff > 0) { // 如果配置了退避时间，则等待
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                    }
                }
            }
        }
        log.error("Node [{}] failed after {} retries.", nodeId, maxRetries);
        throw lastException;
    }
}