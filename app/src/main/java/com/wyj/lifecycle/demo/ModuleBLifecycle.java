package com.wyj.lifecycle.demo;

import android.content.Context;
import android.util.Log;

import com.wyj.annotation.lifecycle.ModuleLifecycle;
import com.wyj.api.IModuleLifecycle;

@ModuleLifecycle
public class ModuleBLifecycle implements IModuleLifecycle {

    @Override
    public int getPriority() {
        return NORM_PRIORITY;
    }

    @Override
    public void onCreate(Context context) {
        Log.d("ModuleLifecycle", "onCreate(): this is in ModuleBLifecycle.");
    }

    @Override
    public void onTerminate() {
        Log.d("ModuleLifecycle", "onTerminate(): this is in ModuleBLifecycle.");
    }
}
