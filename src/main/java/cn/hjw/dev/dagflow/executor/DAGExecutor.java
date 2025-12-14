package cn.hjw.dev.dagflow.executor;

import cn.hjw.dev.dagflow.compile.CompiledNode;
import cn.hjw.dev.dagflow.compile.ExecutionPlan;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.exception.DAGRuntimeException;
import cn.hjw.dev.dagflow.processor.TerminalStrategy;
import cn.hjw.dev.dagflow.processor.UpdateStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class DAGExecutor<T,C,V,R> {

    private final ExecutionPlan<T,C, V> executionPlan;

    private final ExecutorService threadPool;

    private final UpdateStrategy<T,C,V> updateStrategy;

    private final TerminalStrategy<T,C,R> terminalStrategy;

    public DAGExecutor(ExecutionPlan<T,C, V> executionPlan,GraphConfig<T,C,V,R> graphConfig) {

        this.updateStrategy = graphConfig.getUpdateStrategy();
        this.terminalStrategy = graphConfig.getTerminalStrategy();
        this.threadPool = graphConfig.getThreadPool();
        this.executionPlan = executionPlan;
    }

    public R execute(T requestParam, C context) throws Exception {
        List<List<CompiledNode<T, C, V>>> layers = executionPlan.getLayers();
        // --- 外层循环：按层推进 (BSP模型) ---
        for (List<CompiledNode<T,C, V>> layer : layers) {
            if (layer.isEmpty()) continue; // 安全检查，理论上不应出现

            // 1. 【并行阶段】提交当前层所有任务
            // 注意：这里 Node 只是计算，返回 R，不修改 Context
            List<CompletableFuture<V>> futures = layer.stream()
                    .map(
node -> CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    // 执行用户逻辑
                                    return node.getStrategy().process(requestParam, context);
                                } catch (Exception e) {
                                    //将受检异常(BusinessException) 包装为 RuntimeException
                                    log.info("DAG节点执行异常, 节点ID: {} ",node.getNodeId());
                                    throw new DAGRuntimeException("DAG节点执行异常, 节点ID: " + node.getNodeId(),e);
                                }
                            },threadPool)
                    ).collect(Collectors.toList());

            // 2. 【Barrier 等待】
            try {
                // allOf 等待所有的节点完成，设置超时时间为 1 分钟
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(1, TimeUnit.MINUTES);
            } catch (Exception e) {
                // 3. 出错处理：取消所有未完成任务，提取根因并抛出
                // 防止“僵尸任务”继续占用 CPU/IO
                futures.forEach(f -> f.cancel(true));
                // --- 异常分发逻辑 ---
                if (e instanceof TimeoutException) {
                    throw new DAGRuntimeException("DAG Execution Timeout", e);
                } else if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new DAGRuntimeException("DAG主线程被中断", e);
                } else {
                    handleRootCause(extractRealCause(e));
                }
            }

            // 优势：在主线程执行，Context 不需要加锁，UpdateStrategy 实现极其简单
            futures.stream()
                    .map(CompletableFuture::join)
                    .forEach(
                    v -> {
                        try {
                            updateStrategy.updateContext(requestParam, context, v);
                        } catch (Exception e) {
                            throw new DAGRuntimeException("更新异常",e);
                        }
                    }
                    );

            // 循环结束，自动进入下一层（Layer N+1），Layer N+1 的依赖（Layer N）已全部 Ready
        }

        // 应用 TerminalStrategy
        return terminalStrategy.terminate(requestParam, context);
    }

    private Throwable extractRealCause(Throwable throwable) {
        // ExecutionException
        Throwable cause = throwable;
        while ((cause instanceof CompletionException ||
                cause instanceof ExecutionException ||
                cause instanceof DAGRuntimeException)) {

            Throwable next = cause.getCause();
            if (next == null) break;
            cause = next;
        }
        return cause;
    }

    private void handleRootCause(Throwable rootCause) throws Exception {
        log.error("DAG节点执行失败，原因: {}", rootCause.getMessage());
        if (rootCause instanceof Exception) {
            throw (Exception) rootCause;
        } else if (rootCause instanceof Error) {
            throw (Error) rootCause;
        } else {
            throw new RuntimeException("DAG Unknown Error", rootCause);
        }
    }

}
