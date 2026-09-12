package com.citypass.search;

public class SearchCursorExpiredException extends IllegalArgumentException {
    public SearchCursorExpiredException(String message) {
        super(message);
    }
}
