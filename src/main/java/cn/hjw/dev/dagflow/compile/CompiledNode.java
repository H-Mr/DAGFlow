package cn.hjw.dev.dagflow.compile;

import cn.hjw.dev.dagflow.processor.DAGNodeProcessor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 1. 编译后的节点封装
@Getter
@RequiredArgsConstructor
public class CompiledNode<T,C, V> {
    private final String nodeId;
    private final DAGNodeProcessor<T,C, V> strategy;
}
