package cn.hjw.dev.dagflow.executor;

import cn.hjw.dev.dagflow.compile.DAGCompiler;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.config.NodeGovernance;
import cn.hjw.dev.dagflow.exception.DAGRuntimeException;
import cn.hjw.dev.dagflow.hook.FallbackStrategy;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.processor.TerminalStrategy;
import cn.hjw.dev.dagflow.processor.UpstreamInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class DAGExecutor<T, R> {

    private final DAGCompiler.ExecutionPlan<T> plan;
    private final ExecutorService threadPool;
    private final TerminalStrategy<T, R> terminalStrategy;
    private final long globalTimeout;

    public DAGExecutor(DAGCompiler.ExecutionPlan<T> plan, GraphConfig<T, R> config) {
        this.plan = plan;
        this.threadPool = config.getThreadPool();
        this.terminalStrategy = config.getTerminalStrategy();
        this.globalTimeout = config.getGlobalTimeoutMillis() != null ? config.getGlobalTimeoutMillis() : 60000L;
    }

    public R execute(T request) throws Exception {
        // 1. Future 注册表 (Memoization Cache)
        Map<String, CompletableFuture<Object>> futureRegistry = new ConcurrentHashMap<>();

        // 2. 递归构建所有节点的 Future
        // 但为了确保所有孤立节点也能运行，我们遍历所有节点。
        List<CompletableFuture<Object>> allFutures = new ArrayList<>();
        for (String nodeId : plan.getAllNodes()) {
            allFutures.add(getOrCreateFuture(nodeId, request, futureRegistry));
        }

        // 3. 全局等待 (Barrier)
        CompletableFuture<Void> allDone = CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]));

        try {
            allDone.get(globalTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 超时或异常处理：取消所有仍在运行的任务，防止僵尸线程
            allFutures.forEach(f -> f.cancel(true));
            if (e instanceof TimeoutException) {
                throw new DAGRuntimeException("Global DAG execution timed out", e);
            }
            Throwable rootCause = extractRealCause(e);
            log.error("DAG execution failed. Root cause: {}", rootCause.getMessage());
            // D. 抛出根因 (优先抛出非受检异常，让业务层无感捕获)
            if (rootCause instanceof RuntimeException) {
                throw (RuntimeException) rootCause;
            } else if (rootCause instanceof Error) {
                throw (Error) rootCause;
            } else {
                throw new DAGRuntimeException("DAG execution failed", (Exception) rootCause);
            }
        }

        // 4. 收集结果 (Execution Context)
        Map<String, Object> results = new ConcurrentHashMap<>();
        for (Map.Entry<String, CompletableFuture<Object>> entry : futureRegistry.entrySet()) {
            String nodeId = entry.getKey();
            CompletableFuture<Object> future = entry.getValue();
            // 只有成功完成的任务才有结果；失败/被取消的任务没有结果
            if (!future.isCompletedExceptionally() && !future.isCancelled()) {
                Object val = future.getNow(null); // 安全获取，因为前面已经 get() 过了
                if (val != null) {
                    results.put(nodeId, val);
                }
            }
        }

        // 5. 终结策略
        return terminalStrategy.terminate(request, results);
    }

    // 递归构建 Future (核心算法)
    private CompletableFuture<Object> getOrCreateFuture(String nodeId, T request,
                                                        Map<String, CompletableFuture<Object>> registry) {
        // Memoization
        if (registry.containsKey(nodeId)) {
            return registry.get(nodeId);
        }

        // 1. 获取依赖 (Parents)
        List<String> parentIds = plan.getNodeParentsMap().get(nodeId);
        List<CompletableFuture<Object>> parentFutures = new ArrayList<>();
        if (parentIds != null && !parentIds.isEmpty()) {
            for (String parentId : parentIds) {
                parentFutures.add(getOrCreateFuture(parentId, request, registry));
            }
        }

        // 2. 构建当前节点的 Future
        CompletableFuture<Object> currentFuture;

        if (parentFutures != null && ! parentFutures.isEmpty()) {
            // 有依赖，等待所有 Parent 完成
            currentFuture = CompletableFuture.allOf(parentFutures.toArray(new CompletableFuture[0]))
                    .thenApplyAsync(v -> {
                        // 构建输入：收集所有 Parent 的结果
                        Map<String, Object> parentResults = new ConcurrentHashMap<>();
                        for (int i = 0; i < parentIds.size(); i++) {
                            String pid = parentIds.get(i);
                            CompletableFuture<Object> pf = parentFutures.get(i);
                            // 注意：如果 Parent 异常，allOf 会抛异常，进不到这里。
                            // 实现了级联失败 (Cascade Failure)。
                            parentResults.put(pid, pf.join());
                        }
                        return executeNodeLogic(nodeId, request, parentResults);
                    }, threadPool);

        } else {
            // 无依赖，直接提交
            currentFuture = CompletableFuture.supplyAsync(() -> executeNodeLogic(nodeId, request, Map.of()), threadPool);
        }

        // 3. 应用治理 (Timeout & Fallback)
        NodeGovernance governance = plan.getGovernances().get(nodeId);
        if (governance != null) {
            // Node Timeout
            if (governance.getTimeout() > 0) {
                currentFuture = currentFuture.orTimeout(governance.getTimeout(), governance.getTimeUnit());
            }
            // Fallback (处理自身的异常 或 依赖失败导致的异常)
            if (governance.getFallbackStrategy() != null) {
                currentFuture = currentFuture.exceptionally(ex -> {
                    // 构建一个临时的 Input，此时可能只有部分上游数据，或者为空
                    // 简便起见，Fallback 时的 input 可以是一个空的或者尽力而为的视图
                    // 在此模型下，如果是因为上游失败触发的 exceptionally，我们很难拿到上游结果
                    // 所以给 Fallback 传一个空的 Input 是合理的，或者只传 request
                    UpstreamInput emptyInput = new SimpleUpstreamInput(Map.of());

                    Throwable cause = extractRealCause(ex);
                    log.warn("Node [{}] failed/timed-out, triggering fallback. Cause: {}", nodeId, cause.getMessage());

                    try {
                        return ((FallbackStrategy<T>)governance.getFallbackStrategy()).fallback(request, emptyInput, cause);
                    } catch (Exception fbEx) {
                        log.error("Node [{}] fallback failed.", nodeId, fbEx);
                        throw new DAGRuntimeException("Fallback failed", fbEx);
                    }
                });
            }
        }

        // 存入缓存
        registry.put(nodeId, currentFuture);
        return currentFuture;
    }

    // 执行节点具体的业务逻辑
    private Object executeNodeLogic(String nodeId, T request, Map<String, Object> parentResults) {
        try {
            DAGNodeProcessor<T> processor = plan.getProcessors().get(nodeId);
            UpstreamInput input = new SimpleUpstreamInput(parentResults);
            return processor.process(request, input);
        } catch (Exception e) {
            throw new DAGRuntimeException("Node execution failed: " + nodeId, e);
        }
    }

    // 内部类：UpstreamInput 实现
    @RequiredArgsConstructor
    private static class SimpleUpstreamInput implements UpstreamInput {
        private final Map<String, Object> data;

        @Override
        public <V> V get(String nodeId, Class<V> type) {
            Object obj = data.get(nodeId);
            if (obj == null) {
                // 这里的语义：如果依赖的节点成功执行了但返回 null，这是允许的。
                // 如果依赖的节点没在 map 里（说明没执行或失败），这在正常流程（非 fallback）里不应发生
                return null;
            }
            if (!type.isInstance(obj)) {
                throw new ClassCastException("Node [" + nodeId + "] result is " + obj.getClass().getName() + ", expected " + type.getName());
            }
            return type.cast(obj);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V get(String nodeId) {
            return (V) data.get(nodeId);
        }
    }

    /**
     * 递归剥离包装异常，获取最底层的业务异常
     */
    private Throwable extractRealCause(Throwable throwable) {
        Throwable cause = throwable;
        // 循环剥离 CompletionException, ExecutionException, DAGRuntimeException
        while (cause != null && (
                cause instanceof CompletionException ||
                        cause instanceof ExecutionException ||
                        cause instanceof DAGRuntimeException)) {

            Throwable next = cause.getCause();
            if (next == null) {
                break; // 如果没有 cause 了，那当前这个就是最底层的
            }
            cause = next;
        }
        return cause;
    }
}