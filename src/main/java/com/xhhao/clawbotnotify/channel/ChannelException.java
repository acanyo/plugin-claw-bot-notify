package com.xhhao.clawbotnotify.channel;

/** Contains only a local error category, never upstream bodies, headers or URLs. */
public class ChannelException extends RuntimeException {
    public enum Code {
        NETWORK,
        TIMEOUT,
        HTTP_REJECTED,
        BUSINESS_REJECTED,
        INVALID_RESPONSE,
        UNSAFE_DESTINATION,
        CONTEXT_UNAVAILABLE
    }

    private final Code code;

    public ChannelException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
