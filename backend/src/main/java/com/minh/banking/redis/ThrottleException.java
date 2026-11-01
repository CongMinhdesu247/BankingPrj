package com.minh.banking.redis;

import com.minh.banking.common.exception.*;

public class ThrottleException extends BusinessException {
    private final long retryAfter;
    public ThrottleException(ErrorCode code,String message,long retryAfter) {
        super(code,message); this.retryAfter=retryAfter;
    }
    public long retryAfter() { return retryAfter; }
    public static ThrottleException limited(long milliseconds) {
        return new ThrottleException(ErrorCode.RATE_LIMITED,"Too many attempts. Please retry later.",Math.max(1,(milliseconds+999)/1000));
    }
    public static ThrottleException unavailable() {
        return new ThrottleException(ErrorCode.THROTTLE_UNAVAILABLE,"Request protection is temporarily unavailable.",1);
    }
}
