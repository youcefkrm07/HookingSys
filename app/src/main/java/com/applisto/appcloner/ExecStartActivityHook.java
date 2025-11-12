package com.applisto.appcloner;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityResult;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Chain-of-responsibility hook around Instrumentation#execStartActivity.
 */
public abstract class ExecStartActivityHook {
    private static final String TAG = "ExecStartActivityHook";

    private static final List<ExecStartActivityHook> HOOKS = new ArrayList<>();
    private static boolean sInstalled = false;

    /**
     * Arguments passed to execStartActivity
     */
    public static class ExecStartActivityArgs {
        public Context who;
        public IBinder contextThread;
        public IBinder token;
        public Activity target;
        public Intent intent;
        public int requestCode;
        public Bundle options;
        
        public ExecStartActivityArgs(Context who, IBinder contextThread, IBinder token,
                                      Activity target, Intent intent, int requestCode, Bundle options) {
            this.who = who;
            this.contextThread = contextThread;
            this.token = token;
            this.target = target;
            this.intent = intent;
            this.requestCode = requestCode;
            this.options = options;
        }
    }

    /**
     * Register a hook. Should be called early (e.g., Application.onCreate).
     */
    public static synchronized void register(ExecStartActivityHook hook) {
        if (hook == null) {
            return;
        }
        
        if (!HOOKS.contains(hook)) {
            HOOKS.add(hook);
            Log.d(TAG, "Registered hook: " + hook.getClass().getSimpleName());
        }
        
        if (!sInstalled) {
            installInstrumentation();
        }
    }

    /**
     * Unregister a hook
     */
    public static synchronized void unregister(ExecStartActivityHook hook) {
        HOOKS.remove(hook);
        Log.d(TAG, "Unregistered hook: " + hook.getClass().getSimpleName());
    }

    /**
     * Install our custom Instrumentation
     */
    private static void installInstrumentation() {
        try {
            // Get current ActivityThread
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
            Object activityThread = currentActivityThread.invoke(null);

            // Get mInstrumentation field
            Field instrumentationField = activityThreadClass.getDeclaredField("mInstrumentation");
            instrumentationField.setAccessible(true);
            Instrumentation original = (Instrumentation) instrumentationField.get(activityThread);

            // Create and set our custom Instrumentation
            HookInstrumentation hookInstrumentation = new HookInstrumentation(original);
            instrumentationField.set(activityThread, hookInstrumentation);

            sInstalled = true;
            Log.i(TAG, "Successfully installed HookInstrumentation");

        } catch (Exception e) {
            Log.e(TAG, "Failed to install HookInstrumentation", e);
        }
    }

    /**
     * Subclasses implement this to handle/modify activity starts.
     * Return null to pass through to next hook or original implementation.
     * Return non-null ActivityResult to short-circuit and return that result.
     */
    protected abstract ActivityResult onExecStartActivity(ExecStartActivityArgs args);

    /**
     * Custom Instrumentation that delegates to registered hooks
     */
    private static class HookInstrumentation extends Instrumentation {
        private final Instrumentation mBase;

        HookInstrumentation(Instrumentation base) {
            this.mBase = base;
        }

        @Override
        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, Bundle options) {

            // Create args object
            ExecStartActivityArgs args = new ExecStartActivityArgs(
                who, contextThread, token, target, intent, requestCode, options
            );

            // Chain through hooks
            synchronized (ExecStartActivityHook.class) {
                for (ExecStartActivityHook hook : HOOKS) {
                    try {
                        ActivityResult result = hook.onExecStartActivity(args);
                        if (result != null) {
                            // Hook handled it, return result
                            Log.d(TAG, "Hook " + hook.getClass().getSimpleName() + " intercepted intent");
                            return result;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Hook " + hook.getClass().getSimpleName() + " threw exception", e);
                    }
                }
            }

            // No hook handled it, call original
            try {
                Method execStartActivity = Instrumentation.class.getDeclaredMethod(
                    "execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class
                );
                execStartActivity.setAccessible(true);
                return (ActivityResult) execStartActivity.invoke(
                    mBase, who, contextThread, token, target, intent, requestCode, options
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to call original execStartActivity", e);
                return null;
            }
        }

        // Delegate other methods to base Instrumentation
        @Override
        public void callActivityOnCreate(Activity activity, Bundle bundle) {
            mBase.callActivityOnCreate(activity, bundle);
        }

        @Override
        public void callActivityOnDestroy(Activity activity) {
            mBase.callActivityOnDestroy(activity);
        }

        @Override
        public void callActivityOnPause(Activity activity) {
            mBase.callActivityOnPause(activity);
        }

        @Override
        public void callActivityOnResume(Activity activity) {
            mBase.callActivityOnResume(activity);
        }

        @Override
        public void callActivityOnStart(Activity activity) {
            mBase.callActivityOnStart(activity);
        }

        @Override
        public void callActivityOnStop(Activity activity) {
            mBase.callActivityOnStop(activity);
        }
    }
}
