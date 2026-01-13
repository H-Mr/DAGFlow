package cn.hjw.dev.dagflow.engine;

import cn.hjw.dev.dagflow.ExecutableGraph;
import cn.hjw.dev.dagflow.compile.DAGCompiler;
import cn.hjw.dev.dagflow.config.GraphConfig;
import cn.hjw.dev.dagflow.executor.DAGExecutor;

/**
 * 全异步DAG流程执行引擎实例
 * @param <T>
 * @param <R>
 */
public class DAGEngine<T, R> implements ExecutableGraph<T, R> {

    private final DAGExecutor<T, R> executor; // DAG 执行器

    /**
     *
     * @param graphConfig DAG图配置信息，用于创建DAG
     */
    public DAGEngine(GraphConfig<T, R> graphConfig) {
        // 编译，使用编译器将传入的图配置编译成执行计划：1. 检测传入的配置是否有环. 2. 构建节点的反向依赖关系表. 3. 将每个节点治理策略包装起来
        DAGCompiler.ExecutionPlan<T> plan = DAGCompiler.compile(graphConfig);
        // 创建执行器：传入生成好的执行计划和配置实例化执行器
        this.executor = new DAGExecutor<>(plan, graphConfig);
    }

    @Override
    public R apply(T request) throws Exception {
        return executor.execute(request); // 每次调用图，执行器动态根据上下文编译本次涉及的DAG节点
    }
}