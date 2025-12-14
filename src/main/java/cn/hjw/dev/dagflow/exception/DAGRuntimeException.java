package cn.hjw.dev.dagflow.exception;

// 这是一个继承自 RuntimeException 的包装类，仅用于在 CompletableFuture 内部传输
public class DAGRuntimeException extends RuntimeException{

    public DAGRuntimeException(String message,Exception ex) {
        super(message,ex);
    }

}
