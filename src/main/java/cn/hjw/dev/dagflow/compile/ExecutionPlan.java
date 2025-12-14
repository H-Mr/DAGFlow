package cn.hjw.dev.dagflow.compile;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class ExecutionPlan<T,C,V> {

    private final List<List<CompiledNode<T,C, V>>> layers;

}
