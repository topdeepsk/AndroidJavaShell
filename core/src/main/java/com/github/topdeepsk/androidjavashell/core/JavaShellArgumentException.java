package com.github.topdeepsk.androidjavashell.core;

public class JavaShellArgumentException extends IllegalArgumentException {

    public JavaShellArgumentException(String message) {
        super(message);
    }

    public JavaShellArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
