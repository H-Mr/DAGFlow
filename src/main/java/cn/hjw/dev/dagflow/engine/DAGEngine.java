package cn.hjw.dev.dagflow.engine;

import cn.hjw.dev.dagflow.ExecutableGraph;
import cn.hjw.dev.dagflow.compile.DAGCompiler;
import cn.hjw.dev.dagflow.compile.ExecutionPlan;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.executor.DAGExecutor;

public class DAGEngine<T,C,V,R> implements ExecutableGraph<T,C,R> {

    // 持有编译好的执行器实例，而不是工厂
    private final DAGExecutor<T,C,V,R> singletonExecutor;

    public DAGEngine(GraphConfig<T,C,V,R> graphConfig) {
        // 1. 在启动时完成编译 (Fail Fast: 如果有环，启动时就会报错)
        ExecutionPlan<T,C,V> plan = DAGCompiler.compile(graphConfig);
        singletonExecutor = new DAGExecutor<>(plan,graphConfig);
    }

    @Override
    public R apply(T request, C context) throws Exception {
        return singletonExecutor.execute(request, context);
    }
}
