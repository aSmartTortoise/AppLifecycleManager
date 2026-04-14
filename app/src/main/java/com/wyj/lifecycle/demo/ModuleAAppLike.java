package com.wyj.lifecycle.demo;

import android.content.Context;
import android.util.Log;

import com.wyj.annotation.lifecycle.AppLifeCycle;
import com.wyj.api.IAppLike;


@AppLifeCycle
public class ModuleAAppLike implements IAppLike {

    @Override
    public int getPriority() {
        return NORM_PRIORITY;
    }

    @Override
    public void onCreate(Context context) {
        Log.d("AppLike", "onCreate(): this is in ModuleAAppLike.");
    }

    @Override
    public void onTerminate() {
        Log.d("AppLike", "onTerminate(): this is in ModuleAAppLike.");
    }
}
