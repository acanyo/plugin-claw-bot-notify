package com.xhhao.clawbotnotify.halo;

/** Sanitized classification. A non-rejected write may already have taken effect. */
public class HaloWriteException extends RuntimeException {
    private final boolean rejected;

    public HaloWriteException(String code, boolean rejected) {
        super(code);
        this.rejected = rejected;
    }

    public boolean rejected() {
        return rejected;
    }
}
