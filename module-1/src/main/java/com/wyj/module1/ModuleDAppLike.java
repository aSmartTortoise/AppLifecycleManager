package com.wyj.module1;

import android.content.Context;
import android.util.Log;

import com.wyj.annotation.lifecycle.AppLifeCycle;
import com.wyj.api.IAppLike;

@AppLifeCycle
public class ModuleDAppLike implements IAppLike {

    @Override
    public int getPriority() {
        return 7;
    }

    @Override
    public void onCreate(Context context) {
        Log.d("AppLike", "onCreate(): this is in ModuleDAppLike.");
    }

    @Override
    public void onTerminate() {
        Log.d("AppLike", "onTerminate(): this is in ModuleDAppLike.");
    }
}
