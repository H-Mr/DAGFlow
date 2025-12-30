package cn.hjw.dev.dagflow.hook;

import cn.hjw.dev.dagflow.processor.UpstreamInput;

/**
 * 降级策略接口
 * @param <T> 请求类型
 */
@FunctionalInterface
public interface FallbackStrategy<T> {

    /**
     * 执行降级逻辑
     * @param request 原始请求
     * @param input 上游输入
     * @param cause 失败原因
     * @return 兜底结果
     */
    Object fallback(T request, UpstreamInput input, Throwable cause);
}