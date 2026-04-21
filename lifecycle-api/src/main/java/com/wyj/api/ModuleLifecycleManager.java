package com.wyj.api;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.wyj.annotation.lifecycle.LifeCycleConfig;
import com.wyj.api.utils.ClassUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;



public class ModuleLifecycleManager {

    public static boolean DEBUG = false;

    private static List<IModuleLifecycle> MODULE_LIFECYCLE_LIST = new ArrayList<>();
    private static boolean REGISTER_BY_PLUGIN = false;
    private static boolean INIT = false;

    /**
     * 通过插件加载 IModuleLifecycle 类
     */
    private static void loadModuleLifecycle() {
    }

    private static void registerModuleLifecycle(String className) {
        if (TextUtils.isEmpty(className))
            return;
        try {
            Object obj = Class.forName(className).getConstructor().newInstance();
            if (obj instanceof IModuleLifecycle) {
                registerModuleLifecycle((IModuleLifecycle) obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册 IModuleLifecycle
     *
     */
    private static void registerModuleLifecycle(IModuleLifecycle moduleLifecycle) {
        //标志我们已经通过插件注入代码了
        REGISTER_BY_PLUGIN = true;
        MODULE_LIFECYCLE_LIST.add(moduleLifecycle);
    }

    /**
     * 初始化
     *
     * @param context
     */
    public static void init(Context context) {
        if (INIT)
            return;
        INIT = true;
        loadModuleLifecycle();
        if (!REGISTER_BY_PLUGIN) {
            Log.d("ModuleLifecycle", "需要扫描所有类...");
            scanClassFile(context);
        } else {
            Log.d("ModuleLifecycle", "插件里已自动注册...");
        }

        Collections.sort(MODULE_LIFECYCLE_LIST, new PriorityComparator());
        for (IModuleLifecycle moduleLifecycle : MODULE_LIFECYCLE_LIST) {
            moduleLifecycle.onCreate(context);
        }
    }

    public static void terminate() {
        for (IModuleLifecycle moduleLifecycle : MODULE_LIFECYCLE_LIST) {
            moduleLifecycle.onTerminate();
        }
    }

    /**
     * 扫描出固定包名下，实现了 IModuleLifecycle 接口的代理类
     *
     * @param context
     */
    private static void scanClassFile(Context context) {
        try {
            Set<String> set = ClassUtils.getFileNameByPackageName(context, LifeCycleConfig.PROXY_CLASS_PACKAGE_NAME);
            if (set != null) {
                for (String className : set) {
                    if (DEBUG) {
                        Log.d("ModuleLifecycle", className);
                    }
                    try {
                        Object obj = Class.forName(className).newInstance();
                        if (obj instanceof IModuleLifecycle) {
                            MODULE_LIFECYCLE_LIST.add((IModuleLifecycle) obj);
                        }
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 优先级比较器，优先级大的排在前面
     */
    static class PriorityComparator implements Comparator<IModuleLifecycle> {

        @Override
        public int compare(IModuleLifecycle o1, IModuleLifecycle o2) {
            int p1 = o1.getPriority();
            int p2 = o2.getPriority();
            return p2 - p1;
        }
    }

}
