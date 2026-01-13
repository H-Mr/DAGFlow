package cn.hjw.dev.dagflow.executor;

import cn.hjw.dev.dagflow.compile.DAGCompiler;
import cn.hjw.dev.dagflow.condition.EdgeCondition;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.gov.NodeGovernance;
import cn.hjw.dev.dagflow.exception.DAGRuntimeException;
import cn.hjw.dev.dagflow.hook.FallbackStrategy;
import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import cn.hjw.dev.dagflow.hook.TerminalStrategy;
import cn.hjw.dev.dagflow.ctx.UpstreamInput;
import lombok.Getter;
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
    private final GraphConfig<T,R> config;

    public DAGExecutor(DAGCompiler.ExecutionPlan<T> plan, GraphConfig<T, R> config) {
        this.plan = plan;
        this.threadPool = config.getThreadPool();
        this.terminalStrategy = config.getTerminalStrategy();
        this.config = config;
        this.globalTimeout = config.getGlobalTimeoutMillis() != null ? config.getGlobalTimeoutMillis() : 60000L;
    }

    public R execute(T request) throws Exception {
        // 1. Future 注册表，记忆化创建，保证每个节点只创建一次 Future
        Map<String, CompletableFuture<NodeEntry>> futureRegistry = new ConcurrentHashMap<>();
        // 2. 递归构建
        List<CompletableFuture<NodeEntry>> allFutures = new ArrayList<>();
        plan.getAllNodes().forEach(id -> allFutures.add(getOrCreateFuture(id, request, futureRegistry)));

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

        // 4. 收集结果 (只收集 SUCCESS 的结果)
        Map<String, Object> results = new ConcurrentHashMap<>();
        for (Map.Entry<String, CompletableFuture<NodeEntry>> entry : futureRegistry.entrySet()) {
            String nodeId = entry.getKey();
            CompletableFuture<NodeEntry> future = entry.getValue();

            if (!future.isCompletedExceptionally() && !future.isCancelled()) {
                NodeEntry nodeEntry = future.getNow(null);
                // 关键点：如果是 SKIPPED，不放入 results，对 TerminalStrategy 透明
                if (nodeEntry != null && nodeEntry.getStatus() == NodeStatus.SUCCESS) {
                    if (nodeEntry.getValue() != null) {
                        results.put(nodeId, nodeEntry.getValue());
                    }
                }
            }
        }

        // 5. 终结策略
        return terminalStrategy.terminate(request, results);
    }

    // 递归构建 Future
    private CompletableFuture<NodeEntry> getOrCreateFuture(String nodeId, T request,
                                                        Map<String, CompletableFuture<NodeEntry>> registry) {
        // 如果当前节点的 Future 已创建，直接返回
        if (registry.containsKey(nodeId)) {
            return registry.get(nodeId);
        }
        List<String> parentIds = plan.getNodeParentsMap().get(nodeId); // 获取父节点列表，先创建父节点的 Future
        List<CompletableFuture<NodeEntry>> parentFutures = new ArrayList<>();
        if (parentIds != null) {
            parentIds.forEach( id -> parentFutures.add(getOrCreateFuture(id, request, registry)));
        }

        CompletableFuture<NodeEntry> currentFuture;
        // 2. 构建当前节点的 Future
        if (parentFutures.isEmpty()) {
            // 根节点：直接运行，无边条件检查
            currentFuture = CompletableFuture.supplyAsync(() -> executeNodeLogicWithCondition(nodeId,request, Map.of()), threadPool);
        }
        else {
            currentFuture = CompletableFuture.allOf(parentFutures.toArray(new CompletableFuture[0])) // 等待所有父节点完成
                    .thenApplyAsync(v -> {
                        // 1. 检查父节点状态
                        Map<String, Object> parentResults = new ConcurrentHashMap<>();
                        for(CompletableFuture<NodeEntry> par : parentFutures){
                            NodeEntry pEntry = par.join(); // Safe join
                            // 父节点有跳过的，当前节点也跳过
                            if (pEntry.getStatus() == NodeStatus.SKIPPED) {
                                log.info("Node [{}] skipped because parent [{}] was skipped.", nodeId, pEntry.nodeId);
                                return NodeEntry.skipped(nodeId);
                            }
                            if (pEntry.getValue() != null) {
                                parentResults.put(pEntry.nodeId, pEntry.getValue());
                            }
                        }
                        // 检查边条件
                        for(String pId : parentIds){
                            EdgeCondition<T> condition = plan.getEdgeConditions().get(config.buildEdgeKey(pId, nodeId));
                            boolean pass;
                            try {
                                pass =condition.evaluate(request,new SimpleUpstreamInput(parentResults));
                            } catch (Exception ex) {
                                throw new DAGRuntimeException("Edge Condition Failed: " + pId + "->" + nodeId, ex);
                            }
                            if(!pass) {
                                log.info("Node [{}] skipped because edge condition from parent [{}] evaluated to false.", nodeId, pId);
                                return NodeEntry.skipped(nodeId);
                            }
                        }
                        // 2. 父节点都正常，执行核心逻辑
                        return executeNodeLogicWithCondition(nodeId, request, parentResults);
                    },threadPool);
        }

        // 3. 治理 (Timeout & Fallback)
        NodeGovernance gov = plan.getGovernances().get(nodeId);
        if (gov != null) {
            if (gov.getTimeout() > 0) {
                currentFuture = currentFuture.orTimeout(gov.getTimeout(), gov.getTimeUnit());
            }
            if (gov.getFallbackStrategy() != null) {
                currentFuture = currentFuture.exceptionally(ex -> {
                    UpstreamInput emptyInput = new SimpleUpstreamInput(Map.of());
                    Throwable cause = (ex instanceof CompletionException) ? ex.getCause() : ex;
                    log.error("Node [{}] failed, triggering fallback. Cause: {}", nodeId, cause.getMessage());
                    try {
                        Object fbVal = ((FallbackStrategy<T>) gov.getFallbackStrategy()).fallback(request, emptyInput, cause);
                        // 降级成功，视为 SUCCESS
                        return NodeEntry.success(nodeId,fbVal);
                    } catch (Exception fbEx) {
                        throw new DAGRuntimeException("Fallback failed", fbEx);
                    }
                });
            }
        }

        // 存入缓存
        registry.put(nodeId, currentFuture);
        return currentFuture;
    }

    /**
     * 统一封装：条件检查 -> 业务执行
     */
    private NodeEntry executeNodeLogicWithCondition(String nodeId, T request, Map<String, Object> parentResults) {
        UpstreamInput input = new SimpleUpstreamInput(parentResults);
        // 2. 执行业务逻辑
        try {
            DAGNodeProcessor<T> processor = plan.getProcessors().get(nodeId);
            Object result = processor.process(request, input);
            return NodeEntry.success(nodeId,result);
        } catch (Exception e) {
            throw new DAGRuntimeException("Node execution failed: " + nodeId, e);
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

    // --- 内部状态封装 ---
    private enum NodeStatus { SUCCESS, SKIPPED }

    @Getter
    @RequiredArgsConstructor
    private static class NodeEntry {

        private final String nodeId;
        private final Object value;
        private final NodeStatus status;

        static NodeEntry success(String nodeId,Object val) { return new NodeEntry(nodeId,val, NodeStatus.SUCCESS); }
        static NodeEntry skipped(String nodeId) { return new NodeEntry(nodeId,null, NodeStatus.SKIPPED); }
    }

    // 内部类：UpstreamInput 实现
    @RequiredArgsConstructor
    public static class SimpleUpstreamInput implements UpstreamInput {

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

}