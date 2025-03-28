package com.github.a1k28.interceptoragent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class DynamicInterceptorAgent implements InterceptorAPI {
    private static final Logger log = Logger.getInstance(DynamicInterceptorAgent.class);
    private static DynamicInterceptorAgent INSTANCE;
    private static Instrumentation instrumentation;
    private final ConcurrentHashMap<String, List<MockedMethodModel>> classToMockedMethodMap = new ConcurrentHashMap<>();

    public static void agentmain(String arguments, Instrumentation inst) {
        log.agent("Loading DynamicInterceptorAgent...");
        INSTANCE = new DynamicInterceptorAgent();
        instrumentation = inst;
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    String classname = typeDescription.getActualName();
                    if (INSTANCE.classToMockedMethodMap.containsKey(classname)) {
                        return builder.visit(Advice.to(MethodAdvice.class)
                                .on(isMethod().and(not(isConstructor()))));
                    }
                    return builder;
                }).installOn(inst);
        log.agent("DynamicInterceptorAgent loaded successfully");
    }

    public static DynamicInterceptorAgent getInstance() {
        if (INSTANCE == null) {
            throw new RuntimeException("DynamicClassAgent not initialized. Make sure the agent is loaded.");
        }
        return INSTANCE;
    }

    @Override
    public void mockMethodReturns(Method method, Object returnVal, Object... args) {
        if (returnVal == null && isBoxedPrimitive(method.getReturnType())) {
            log.warn("Using null return values for Boxed Primitives" +
                    " will result in default primitive values instead of null returns!");
            returnVal = createNullValue(method.getReturnType());
        }

        String classname = method.getDeclaringClass().getName();
        MockedMethodModel mockedMethodModel = new MockedMethodModel(method, returnVal, args);
        classToMockedMethodMap.compute(classname, (k,v) -> {
            if (v == null) v = new ArrayList<>();
            v.add(mockedMethodModel);
            return v;
        });
        retransformClass(classname);
    }

    @Override
    public void mockMethodReturnStub(Class clazz, Method method, Object... args) {
        Object returnVal = createStub(clazz);
        String classname = method.getDeclaringClass().getName();
        MockedMethodModel mockedMethodModel = new MockedMethodModel(method, returnVal, args);
        classToMockedMethodMap.compute(classname, (k,v) -> {
            if (v == null) v = new ArrayList<>();
            v.add(mockedMethodModel);
            return v;
        });
        retransformClass(classname);
    }

    @Override
    public void mockMethodThrows(Method method, Class exceptionType, Object... args) {
        String classname = method.getDeclaringClass().getName();
        MockedMethodModel mockedMethodModel = new MockedMethodModel(method, exceptionType, args);
        classToMockedMethodMap.compute(classname, (k,v) -> {
            if (v == null) v = new ArrayList<>();
            v.add(mockedMethodModel);
            return v;
        });
        retransformClass(classname);
    }

    @Override
    public void resetMockState() {
        classToMockedMethodMap.clear();
    }

    public static class MethodAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean onEnter(@Advice.Origin Method method,
                               @Advice.AllArguments Object[] args) throws Throwable {
            Optional<MockedMethodModel> mockedMethodModel = getMockedModelForInterception(method, args);
            if (mockedMethodModel.isPresent()) {
                if (mockedMethodModel.get().getExceptionType() != null)
                    throw createThrowable(mockedMethodModel.get());
                return true;
            }
            return false;
        }

        @Advice.OnMethodExit
        static void onExit(@Advice.Origin Method method,
                           @Advice.Enter boolean intercepted,
                           @Advice.AllArguments Object[] args,
                           @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned) throws Throwable {
            if (intercepted) {
                Optional<MockedMethodModel> mockedMethodModel = getMockedModelForInterception(method, args);
                if (mockedMethodModel.isPresent()) {
                    if (mockedMethodModel.get().getExceptionType() == null)
                        returned = mockedMethodModel.get().getReturnVal();
                }
            }
        }
    }

    public static Optional<MockedMethodModel> getMockedModelForInterception(
            Method method, Object[] args) {
        String classname = method.getDeclaringClass().getName();
        List<MockedMethodModel> mockedMethods = INSTANCE.classToMockedMethodMap.get(classname);
        if (mockedMethods != null) {
            for (MockedMethodModel mockedMethodModel : INSTANCE.classToMockedMethodMap.get(classname)) {
                if (!methodsMatch(mockedMethodModel.getMethod(), method)) continue;
                if (!parametersMatch(args, mockedMethodModel)) continue;
                return Optional.of(mockedMethodModel);
            }
        }
        return Optional.empty();
    }

    public static Throwable createThrowable(MockedMethodModel mockedMethodModel) {
        try {
            Class<? extends Throwable> exceptionType = mockedMethodModel.getExceptionType();
            try {
                return exceptionType.getDeclaredConstructor().newInstance();
            } catch (Throwable e) {
                log.error("Exception has occurred during throwable instantiation: ", e);
            }
            Constructor constructor = exceptionType.getDeclaredConstructors()[0];
            Class<?>[] argumentTypes = constructor.getParameterTypes();
            Object[] values = new Object[argumentTypes.length];
            for (int i = 0; i < argumentTypes.length; i++) {
                values[i] = createValue(argumentTypes[i]);
            }
            return (Throwable) constructor.newInstance(values);
        } catch (Throwable e) {
            log.error("Could not create an exception: ", e);
            throw new RuntimeException(e);
        }
    }

    private static void retransformClass(String className) {
        if (instrumentation != null) {
            try {
                Class<?> clazz = Class.forName(className);
                if (instrumentation.isModifiableClass(clazz)) {
                    instrumentation.retransformClasses(clazz);
                } else {
                    log.error("Class is not modifiable to retransform: " + className);
                }
            } catch (Exception e) {
                log.error("Cannot retransform class: ", e);
            }
        }
    }

    private static boolean methodsMatch(Method m1, Method m2) {
        return m1.getName().equals(m2.getName()) &&
                m1.getReturnType().equals(m2.getReturnType()) &&
                Arrays.equals(m1.getParameterTypes(), m2.getParameterTypes());
    }

    private static boolean parametersMatch(Object[] args, MockedMethodModel mockedMethodModel) {
        if (args == null ^ mockedMethodModel.getArgs() == null) return false;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null ^ mockedMethodModel.getArgs()[i] == null)
                    return false;
                if (args[i] != null) {
                    if (args[i] instanceof ArgumentType argumentType) {
                        // TODO: match parameter
//                        Class type = mockedMethodModel.getMethod().getParameterTypes()[i];
                    } else if (!args[i].equals(mockedMethodModel.getArgs()[i])) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static <T> T createStub(Class<T> classToStub) {
        log.agent("Creating Stub for: " + classToStub);
        DynamicType.Builder<? extends T> builder = new ByteBuddy()
                .subclass(classToStub)
                .name(classToStub.getName() + "Stub");

        // For each method in the original class, override it with an empty implementation
        for (Method method : classToStub.getDeclaredMethods()) {
            if (!Modifier.isFinal(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())) {
                builder = builder
                        .visit(Advice.to(MethodAdvice.class)
                        .on(isMethod().and(not(isConstructor()))));
            }
        }

        // Create the stub class
        Class<? extends T> stubClass = builder
                .make()
                .load(classToStub.getClassLoader())
                .getLoaded();

        try {
            // Create an instance of the stub class
            return stubClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Implementation getImplementationForMethod(Method method) {
        Class<?> returnType = method.getReturnType();

        if (returnType.equals(void.class)) {
            return StubMethod.INSTANCE;
        } else if (returnType.equals(boolean.class)) {
            return FixedValue.value(false);
        } else if (returnType.equals(byte.class)) {
            return FixedValue.value((byte) 0);
        } else if (returnType.equals(short.class)) {
            return FixedValue.value((short) 0);
        } else if (returnType.equals(int.class)) {
            return FixedValue.value(0);
        } else if (returnType.equals(long.class)) {
            return FixedValue.value(0L);
        } else if (returnType.equals(float.class)) {
            return FixedValue.value(0.0f);
        } else if (returnType.equals(double.class)) {
            return FixedValue.value(0.0d);
        } else if (returnType.equals(char.class)) {
            return FixedValue.value('\u0000');
        } else {
            return FixedValue.nullValue();
        }
    }

    private static Object createValue(Class<?> type) {
        if (type == boolean.class)
            return false;
        if (type == byte.class)
            return 0;
        if (type == short.class)
            return 0;
        if (type == char.class)
            return '\u0000';
        if (type == int.class)
            return 0;
        if (type == long.class)
            return 0;
        if (type == float.class)
            return 0;
        if (type == double.class)
            return 0;
        return null;
    }

    // TODO: handle boxed primitives
    private static Object createNullValue(Class<?> type) {
        if (type == Boolean.class)
            return false;
        if (type == Byte.class)
            return 0;
        if (type == Short.class)
            return 0;
        if (type == Character.class)
            return '\u0000';
        if (type == Integer.class)
            return 0;
        if (type == Long.class)
            return 0;
        if (type == Float.class)
            return 0;
        if (type == Double.class)
            return 0;
        return null;
    }

    private static boolean isBoxedPrimitive(Class<?> type) {
        return type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Character.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class;
    }
}

