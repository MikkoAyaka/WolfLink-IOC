package org.wolflink.common.ioc;

import lombok.NonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class IOC {

    // 存储单例对象的映射表
    private static final ConcurrentHashMap<Class<?>, Object> singletonMap = new ConcurrentHashMap<>();
    // 存储所有线程的busyClass堆栈的映射表
    private static final ConcurrentHashMap<Long, ArrayDeque<Class<?>>> busyClassMap = new ConcurrentHashMap<>();

    // 常量定义
    private static final String LOOPBACK_ERROR = "不允许回环依赖注入: ";
    private static final String NULL_RESULT = " 实例化结果为 null";
    private static final String BEAN_PROVIDER_SHADOWED =
            "在注册 BeanProvider 时出现冲突，数据已被以下信息覆盖：\n"
            +"Bean 类型：%type%\n"
            +"Bean 提供者方法：%provider%\n"
            +"Bean 配置类：%config%";
    private static final String BEAN_NO_CONSTRUCTOR =
            "未能找到无参构造方法，相关类：\n"
            +"PackageName：%package_name%\n"
            +"Name：%class_name%";
    private static final String BEAN_CONSTRUCTOR_SECURITY =
            "无权访问无参构造方法，相关类：\n"
            +"PackageName：%package_name%\n"
            +"Name：%class_name%";
    private static final Map<Class<?>, Supplier<Object>> beanProviders = new ConcurrentHashMap<>();
    // 添加私有constructor防止实例化工具类
    private IOC() {
        throw new IllegalStateException("Utility class");
    }

    public static void registerBeanConfig(Class<?> beanConfigClass) {
        registerBeanConfig(getBean(beanConfigClass));
    }
    /**
     * 传入 Bean 配置类，通过反射扫描获取其中的 BeanProvider 注解获取提供者方法
     * 将方法封装到 Supplier
     * 目前只支持通过类型识别对应的 BeanProvider
     * 也就是相同类型的提供方法只应该存在一个，否则会出现被覆盖的情况
     */
    public static void registerBeanConfig(Object beanConfig) {
        Class<?> beanConfigClass = beanConfig.getClass();
        // 扫描当前实例类的方法和注解
        scanAndRegisterBeanProvider(beanConfig,beanConfigClass);
        // 扫描实例的父类和接口的方法和注解
        Class<?> superClass = beanConfigClass.getSuperclass();
        if(!(superClass.isAssignableFrom(Object.class))) {
            scanAndRegisterBeanProvider(beanConfig,superClass);
        }
        Class<?>[] interfaceClasses = beanConfigClass.getInterfaces();
        for (Class<?> interfaceClass : interfaceClasses) {
            scanAndRegisterBeanProvider(beanConfig,interfaceClass);
        }

    }

    /**
     * 扫描指定类中的方法，将符合条件的 BeanProvider 注册到容器中
     */
    private static void scanAndRegisterBeanProvider(Object object,Class<?> clazz) {
        Arrays.stream(clazz.getDeclaredMethods()).forEach(method -> {
            if(Arrays.stream(method.getAnnotations()).anyMatch(it -> it.annotationType().equals(BeanProvider.class))) {
                Class<?> returnType = method.getReturnType();
                if(beanProviders.containsKey(returnType)) {
                    System.out.println(BEAN_PROVIDER_SHADOWED
                            .replace("%type%",returnType.getName())
                            .replace("%provider%",method.getName())
                            .replace("%config%",object.getClass().getName())
                    );
                }
                beanProviders.put(returnType,()-> registerBeanConfig(object,method));
            }
        });
    }
    private static Object registerBeanConfig(Object object,Method providerMethod) {
        try {
            return providerMethod.invoke(object);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 传入 Bean 配置类，通过反射扫描获取其中的 BeanProvider 注解获取提供者方法
     * 从 Supplier 集合中删除对应类型的提供者
     * 如果存在冲突的配置类，则可能出现一些问题
     */
    public static void unregisterBeanConfig(Object beanConfig) {
        Class<?> beanConfigClass = beanConfig.getClass();
        Arrays.stream(beanConfigClass.getDeclaredMethods()).forEach(method -> {
            if(Arrays.stream(method.getDeclaredAnnotations()).anyMatch(it -> it.annotationType().equals(BeanProvider.class))) {
                beanProviders.remove(method.getReturnType());
            }
        });
    }
    /**
     * 获取给定类的实例
     *
     * @param clazz     Bean Class(用@Singleton标记单例)
     * @param arguments 构造方法参数，如果希望获取的是单例并且IOC容器中已经存在该实例，则可以不传参数列表
     * @return 给定类的实例
     */
    @NonNull
    public static <T> T getBean(Class<? extends T> clazz, Object... arguments) {
        if (singletonMap.containsKey(clazz)) {
            return clazz.cast(singletonMap.get(clazz));
        }
        T result = createBean(clazz, arguments);
        if (result == null) {
            throw new NullPointerException(clazz.getName() + NULL_RESULT);
        }
        return result;
    }

    /**
     * IOC Bean 层面实例创建方法
     * 判断单例 Bean 并存储
     * 完成依赖注入
     */

    private static <T> T createBean(Class<? extends T> clazz, Object... arguments) {
        T result = null;
        ArrayDeque<Class<?>> busyClasses = getBusyClasses();
        try {
            checkCircularDependency(clazz);
            busyClasses.push(clazz);
            result = createInstance(clazz, arguments);
            setField(clazz, result);
            saveSingleton(clazz, result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        busyClasses.pop();
        return result;
    }

    /**
     * 获取当前线程的busyClass堆栈，实际上是一个ArrayDeque
     */
    @NonNull
    private static ArrayDeque<Class<?>> getBusyClasses() {
        return busyClassMap.computeIfAbsent(Thread.currentThread().getId(), k -> new ArrayDeque<>());
    }

    /**
     * 检查是否存在回环依赖
     */
    private static void checkCircularDependency(Class<?> clazz) {
        if (getBusyClasses().contains(clazz)) {
            throw new IllegalArgumentException(LOOPBACK_ERROR + getBusyClasses().stream().map(Class::getName).collect(Collectors.joining(" -> ")));
        }
    }

    /**
     * 反射层实例创建方法
     * 扫描 BeanConfiguration 寻找是否有匹配的 Bean Provider
     * 如果存在则优先考虑从 BeanConfiguration 中获取 Bean
     * 如果不存在则根据构造方法参数创建实例
     */
    @NonNull
    private static <T> T createInstance(@NonNull Class<? extends T> clazz, Object... arguments) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        if (arguments.length == 0) {
            // 遍历 BeanConfigurations 寻找适配的 Bean Provider
            if(beanProviders.containsKey(clazz)) return (T) beanProviders.get(clazz).get();
            else {
                try {
                    return clazz.getDeclaredConstructor().newInstance();
                } catch (NoSuchMethodException e) {
                    System.out.println(BEAN_NO_CONSTRUCTOR
                            .replace("%package_name%",clazz.getPackage().getName())
                            .replace("%class_name%",clazz.getName()));
                } catch (SecurityException e) {
                    System.out.println(BEAN_CONSTRUCTOR_SECURITY
                            .replace("%package_name%",clazz.getPackage().getName())
                            .replace("%class_name%",clazz.getName()));
                }
            }
        }
        Constructor<? extends T> constructor;
        constructor = clazz.getConstructor(Arrays.stream(arguments).map(Object::getClass).toArray(Class[]::new));
        return constructor.newInstance(arguments);
    }

    /**
     * 注入依赖字段
     */
    private static <T> void setField(Class<? extends T> clazz, T result) throws IllegalAccessException {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getAnnotation(Inject.class) != null) {
                if (!Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true);
                }
                field.set(result, getBean(field.getType()));
            }
        }
    }

    /**
     * 如果是单例类，则保存到映射表中
     */
    private static <T> void saveSingleton(@NonNull Class<? extends T> clazz, T result) {
        if (clazz.getAnnotation(Singleton.class) != null) {
            singletonMap.put(clazz, result);
        }
    }
}
