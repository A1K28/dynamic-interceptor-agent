package com.github.a1k28.interceptoragent;

import java.lang.reflect.Method;

public interface InterceptorAPI {
    void mockMethodReturns(Method method, Object returnVal, Object... args);

    void mockMethodReturnStub(Class clazz, Method method, Object... args);

    void mockMethodThrows(Method method, Class exceptionType, Object... args);

    void resetMockState();
}
