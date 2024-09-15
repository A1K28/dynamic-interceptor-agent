package com.github.a1k28.interceptoragent;

import java.lang.reflect.Method;

public class MockedMethodModel {
    private final Method method;
    private final Object[] args;
    private final Class<? extends Throwable> exceptionType;
    private final Object returnVal;

    public MockedMethodModel(Method method, Object returnVal, Object[] args) {
        this.method = method;
        this.args = args;
        this.returnVal = returnVal;
        this.exceptionType = null;
    }

    public MockedMethodModel(Method method, Class<? extends Throwable> exceptionType, Object[] args) {
        this.method = method;
        this.args = args;
        this.returnVal = null;
        this.exceptionType = exceptionType;
    }

    public Method getMethod() {
        return method;
    }

    public Object[] getArgs() {
        return args;
    }

    public Class<? extends Throwable> getExceptionType() {
        return exceptionType;
    }

    public Object getReturnVal() {
        return returnVal;
    }
}
