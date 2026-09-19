package com.citypass.utils;

/** Raised when the bounded database degradation lane has no free capacity. */
public class CacheDegradedException extends RuntimeException {
    public CacheDegradedException(String message) {
        super(message);
    }
}
