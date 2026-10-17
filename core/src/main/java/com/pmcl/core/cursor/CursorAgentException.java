package com.pmcl.core.cursor;

/** 交给智能体看的失败说明。不要把令牌、堆栈或原因链放进来。 */
public final class CursorAgentException extends RuntimeException {
    public CursorAgentException(String message) {
        super(message == null || message.isBlank() ? "failed" : message);
    }
}
