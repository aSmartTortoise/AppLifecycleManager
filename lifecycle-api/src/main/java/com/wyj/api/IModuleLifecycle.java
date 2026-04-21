package com.wyj.api;

import android.content.Context;


public interface IModuleLifecycle {

    int MAX_PRIORITY = 10;
    int MIN_PRIORITY = 1;
    int NORM_PRIORITY = 5;

    int getPriority();

    void onCreate(Context context);

    void onTerminate();

}
