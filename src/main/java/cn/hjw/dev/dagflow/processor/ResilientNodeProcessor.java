package cn.hjw.dev.dagflow.processor;

import cn.hjw.dev.dagflow.config.NodeGovernance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResilientNodeProcessor<T> implements DAGNodeProcessor<T> {

    private final String nodeId;
    private final DAGNodeProcessor<T> delegate;
    private final NodeGovernance governance;

    @Override
    public Object process(T request, UpstreamInput input) throws Exception {
        int maxRetries = governance != null ? governance.getMaxRetries() : 0;
        long backoff = governance != null ? governance.getRetryBackoff() : 0;

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                return delegate.process(request, input);
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt <= maxRetries) {
                    log.warn("Node [{}] failed (attempt {}/{}), retrying...", nodeId, attempt, maxRetries);
                    if (backoff > 0) {
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