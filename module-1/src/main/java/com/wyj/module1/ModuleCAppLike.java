package com.wyj.module1;

import android.content.Context;
import android.util.Log;

import com.wyj.annotation.lifecycle.AppLifeCycle;
import com.wyj.api.IAppLike;


@AppLifeCycle
public class ModuleCAppLike implements IAppLike {

    @Override
    public int getPriority() {
        return MAX_PRIORITY;
    }

    @Override
    public void onCreate(Context context) {
        Log.d("AppLike", "onCreate(): this is in ModuleCAppLike.");
    }

    @Override
    public void onTerminate() {
        Log.d("AppLike", "onTerminate(): this is in ModuleCAppLike.");
    }
}
