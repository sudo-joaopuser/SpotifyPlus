package com.lenerd46.spotifyplus.hooks;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

public class AlwaysKeepRunningHook extends SpotifyHook {
    public static final String PREFERENCE = "always_keep_spotify_running";
    private final Context context;

    public AlwaysKeepRunningHook(Context context) {
        this.context = context;
    }

    private boolean isEnabled(Object thisObject) {
        try {
            Context ctx = context;
            if (ctx == null && thisObject instanceof Context) {
                ctx = (Context) thisObject;
            }
            if (ctx == null) {
                ctx = com.lenerd46.spotifyplus.References.currentActivity;
            }
            if (ctx != null) {
                SharedPreferences prefs = ctx.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
                return prefs.getBoolean(PREFERENCE, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    protected void hook() {
        XC_MethodHook onTaskRemovedHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isEnabled(param.thisObject)) {
                    XposedBridge.log("[SpotifyPlus][DontKillMyApp] onTaskRemoved intercepted on " + param.thisObject.getClass().getName());
                    param.setResult(null);
                }
            }
        };

        XC_MethodHook onStartCommandHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isEnabled(param.thisObject)) {
                    Object res = param.getResult();
                    if (res instanceof Integer && ((Integer) res) == Service.START_NOT_STICKY) {
                        param.setResult(Service.START_STICKY);
                    }
                }
            }
        };

        // 1. Hook base Service.onTaskRemoved & onStartCommand
        try {
            XposedHelpers.findAndHookMethod(Service.class, "onTaskRemoved", Intent.class, onTaskRemovedHook);
            XposedHelpers.findAndHookMethod(Service.class, "onStartCommand", Intent.class, int.class, int.class, onStartCommandHook);
        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus][DontKillMyApp] Failed to hook base Service methods: " + t);
        }

        // 2. Use DexKit to find all classes implementing onTaskRemoved(Intent) in Spotify's APK
        if (bridge != null) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create()
                                .name("onTaskRemoved")
                                .paramTypes(Intent.class)
                ));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(lpparm.classLoader);
                        if (method.getDeclaringClass() != Service.class) {
                            XposedBridge.hookMethod(method, onTaskRemovedHook);
                            XposedBridge.log("[SpotifyPlus][DontKillMyApp] Hooked onTaskRemoved on " + method.getDeclaringClass().getName());
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[SpotifyPlus][DontKillMyApp] Failed to hook method: " + methodData + ": " + t);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[SpotifyPlus][DontKillMyApp] DexKit onTaskRemoved search failed: " + t);
            }
        }
    }
}
