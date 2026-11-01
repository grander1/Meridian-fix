package com.meridian.fix;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class MeridianFix implements IXposedHookLoadPackage {

    private static final String TAG = "[MeridianFix] ";
    private static final String TARGET_PKG = "com.otomaru.themeridian";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + "injected into " + lpparam.packageName);
        try {
            Class<?> dc = XposedHelpers.findClass("android.app.LoadedApk$ServiceDispatcher", lpparam.classLoader);
            for (Field f : dc.getDeclaredFields()) {
                XposedBridge.log(TAG + "field: " + f.getName() + " : " + f.getType().getName());
            }
            boolean hooked = false;
            for (Method m : dc.getDeclaredMethods()) {
                if (!"doConnected".equals(m.getName())) continue;
                XposedBridge.log(TAG + "found doConnected params=" + m.getParameterCount());
                for (Class<?> c : m.getParameterTypes()) XposedBridge.log(TAG + "   param: " + c.getName());
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return doConnectedCompat(param);
                    }
                });
                hooked = true;
            }
            XposedBridge.log(TAG + (hooked ? "hook done" : "doConnected NOT found"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init failed: " + t);
        }
    }

    private Object doConnectedCompat(XC_MethodHook.MethodHookParam param) throws Throwable {
        try {
            Object d = param.thisObject;
            Object o1 = getFieldSafe(d, "mConnection");
            Object o2 = getFieldSafe(d, "mName");
            if (!(o1 instanceof ServiceConnection) || !(o2 instanceof ComponentName)) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }
            ServiceConnection conn = (ServiceConnection) o1;
            ComponentName name = (ComponentName) o2;
            IBinder service = null;
            if (param.args != null) {
                for (Object a : param.args) {
                    if (a instanceof IBinder) { service = (IBinder) a; break; }
                }
            }
            if (service == null) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }
            XposedBridge.log(TAG + "doConnected conn=" + conn.getClass().getName() + " name=" + name.flattenToShortString());
            if (!isUnityProxy(conn)) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }
            XposedBridge.log(TAG + ">>> downgrade to 2-arg onServiceConnected");
            conn.onServiceConnected(name, service);
            XposedBridge.log(TAG + ">>> downgrade OK");
            return null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "doConnectedCompat err: " + t);
            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        }
    }

    private boolean isUnityProxy(Object conn) {
        String cls = conn.getClass().getName();
        if (cls.startsWith("com.android.") || cls.startsWith("android.")) return false;
        if (cls.startsWith("$Proxy")) return true;
        if (Proxy.isProxyClass(conn.getClass())) {
            try {
                InvocationHandler h = Proxy.getInvocationHandler(conn);
                if (h != null) {
                    String hn = h.getClass().getName();
                    if (hn.contains("jnibridge") || hn.contains("JNIBridge") || hn.contains("bitter")) return true;
                }
            } catch (Throwable ignored) {}
            if (cls.indexOf(".") < 0) return true;
        }
        return false;
    }

    private Object getFieldSafe(Object obj, String name) {
        try { return XposedHelpers.getObjectField(obj, name); } catch (Throwable t) { return null; }
    }
}
