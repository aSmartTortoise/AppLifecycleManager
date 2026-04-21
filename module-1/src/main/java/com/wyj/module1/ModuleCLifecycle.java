package com.wyj.module1;

import android.content.Context;
import android.util.Log;

import com.wyj.annotation.lifecycle.ModuleLifecycle;
import com.wyj.api.IModuleLifecycle;


@ModuleLifecycle
public class ModuleCLifecycle implements IModuleLifecycle {

    @Override
    public int getPriority() {
        return MAX_PRIORITY;
    }

    @Override
    public void onCreate(Context context) {
        Log.d("ModuleLifecycle", "onCreate(): this is in ModuleCLifecycle.");
    }

    @Override
    public void onTerminate() {
        Log.d("ModuleLifecycle", "onTerminate(): this is in ModuleCLifecycle.");
    }
}
