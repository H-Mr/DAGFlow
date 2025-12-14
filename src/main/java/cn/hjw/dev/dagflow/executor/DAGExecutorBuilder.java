package cn.hjw.dev.dagflow.executor;


import cn.hjw.dev.dagflow.config.GraphConfig;

@FunctionalInterface
public interface DAGExecutorBuilder<T,C,V,R> {

    /**
     * 创建DAG图执行器
     * @param graphConfig
     * @return
     */
    DAGExecutor<T,C,V,R> build(GraphConfig<T,C,V,R> graphConfig);
}
