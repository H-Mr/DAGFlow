package cn.hjw.dev.dagflow.engine;

import cn.hjw.dev.dagflow.ExecutableGraph;
import cn.hjw.dev.dagflow.compile.DAGCompiler;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.executor.DAGExecutor;

public class DAGEngine<T, R> implements ExecutableGraph<T, R> {

    private final DAGExecutor<T, R> executor;

    public DAGEngine(GraphConfig<T, R> graphConfig) {
        // 编译
        DAGCompiler.ExecutionPlan<T> plan = DAGCompiler.compile(graphConfig);
        // 创建执行器
        this.executor = new DAGExecutor<>(plan, graphConfig);
    }

    @Override
    public R apply(T request) throws Exception {
        return executor.execute(request);
    }
}