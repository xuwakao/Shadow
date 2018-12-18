package com.tencent.shadow.dynamic.host;

import android.text.TextUtils;

import com.tencent.shadow.core.interface_.log.ILogger;
import com.tencent.shadow.core.interface_.log.ShadowLoggerFactory;

import java.lang.reflect.Field;

/**
 * 将runTime apk加载到UUIDClassLoader，形成如下结构的classLoader树结构
 * ---BootClassLoader
 * ----UUIDClassLoader
 * ------PathClassLoader
 */
public class RunTimeLoader {

    private static ILogger mLogger = ShadowLoggerFactory.getLogger("shadow::RunTimeLoader");

    /**
     * 加载runtime apk
     *
     */
    public static void loadRunTime(InstalledPart installedPart) {
        ClassLoader contextClassLoader = RunTimeLoader.class.getClassLoader();
        ClassLoader parent = contextClassLoader.getParent();
        if (parent instanceof UUIDClassLoader) {
            String currentUUID = ((UUIDClassLoader) parent).UUID;
            if (TextUtils.equals(currentUUID, installedPart.UUID)) {
                //已经加载相同版本的runtime了,不需要加载
                if(mLogger.isInfoEnabled()){
                    mLogger.info("已经加载相同UUID版本的runtime了,不需要加载");
                }
                return;
            } else {
                //版本不一样，说明要更新runtime，先恢复正常的classLoader结构
                if(mLogger.isInfoEnabled()){
                    mLogger.info("加载不相同UUID版本的runtime了,更新runtime");
                }
                try {
                    hackParentClassLoader(contextClassLoader, parent.getParent());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } else { //如果parent不是UUIDClassLoader 那么有2种可能，1是加载了历史版本的runtime，需要卸载更新  2是首次加载runtime
            try {
                //DelegateProviderHolder是所有版本的Container都应该有的类
                Class<?> aClass = contextClassLoader.loadClass("com.tencent.shadow.runtime.container.DelegateProviderHolder");
                //没有异常，说明加载过其他版本的Container.需要先恢复contextClassLoader
                try {
                    if(mLogger.isInfoEnabled()){
                        mLogger.info("加载过其他版本的Container.需要先恢复classLoader");
                    }
                    hackParentClassLoader(contextClassLoader, aClass.getClassLoader().getParent());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (ClassNotFoundException ignored) {
                //任何Container都没有加载过
            }
        }
        //正常处理，将runtime 挂到pathclassLoader之上
        try {
            UUIDClassLoader pluginContainerClassLoader = new UUIDClassLoader(installedPart.UUID, installedPart.filePath,
                    installedPart.oDexPath, installedPart.libraryPath, parent);
            hackParentClassLoader(contextClassLoader, pluginContainerClassLoader);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 修改ClassLoader的parent
     *
     * @param classLoader          需要修改的ClassLoader
     * @param newParentClassLoader classLoader的新的parent
     * @throws Exception 失败时抛出
     */
    private static void hackParentClassLoader(ClassLoader classLoader,
                                              ClassLoader newParentClassLoader) throws Exception {
        Field field = getParentField();
        if (field == null) {
            throw new RuntimeException("在ClassLoader.class中没找到类型为ClassLoader的parent域");
        }
        field.setAccessible(true);
        field.set(classLoader, newParentClassLoader);
    }

    /**
     * 安全地获取到ClassLoader类的parent域
     *
     * @return ClassLoader类的parent域.或不能通过反射访问该域时返回null.
     */
    private static Field getParentField() {
        ClassLoader classLoader = RunTimeLoader.class.getClassLoader();
        ClassLoader parent = classLoader.getParent();
        Field field = null;
        for (Field f : ClassLoader.class.getDeclaredFields()) {
            try {
                boolean accessible = f.isAccessible();
                f.setAccessible(true);
                Object o = f.get(classLoader);
                f.setAccessible(accessible);
                if (o == parent) {
                    field = f;
                    break;
                }
            } catch (IllegalAccessException ignore) {
            }
        }
        return field;
    }
}
