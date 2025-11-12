package com.applisto.appcloner;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Chain-of-responsibility hook around Instrumentation#execStartActivity.
 */
public abstract class ExecStartActivityHook {
    private static final String TAG = "ExecStartActivityHook";

    private static final List<ExecStartActivityHook> HOOKS = new ArrayList<>();
    private static boolean sInstalled = false;
    private static Instrumentation sOriginalInstrumentation;

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
     * Install our custom Instrumentation using reflection wrapper
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
            sOriginalInstrumentation = (Instrumentation) instrumentationField.get(activityThread);

            // Create and set our custom Instrumentation
            InstrumentationWrapper wrapper = new InstrumentationWrapper(sOriginalInstrumentation);
            instrumentationField.set(activityThread, wrapper);

            sInstalled = true;
            Log.i(TAG, "Successfully installed InstrumentationWrapper");

        } catch (Exception e) {
            Log.e(TAG, "Failed to install InstrumentationWrapper", e);
        }
    }

    /**
     * Subclasses implement this to handle/modify activity starts.
     * Return null to pass through to next hook or original implementation.
     * Return non-null ActivityResult to short-circuit and return that result.
     */
    protected abstract ActivityResult onExecStartActivity(ExecStartActivityArgs args);

    /**
     * Wrapper class that extends Instrumentation and intercepts method calls
     */
    private static class InstrumentationWrapper extends Instrumentation {
        private final Instrumentation mBase;
        private Method mExecStartActivityMethod;

        InstrumentationWrapper(Instrumentation base) {
            this.mBase = base;
            
            // Cache the execStartActivity method
            try {
                // Try to find the method with standard signature
                mExecStartActivityMethod = Instrumentation.class.getDeclaredMethod(
                    "execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class
                );
                mExecStartActivityMethod.setAccessible(true);
                Log.d(TAG, "Found execStartActivity method");
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "Could not find execStartActivity method", e);
            }
        }

        /**
         * This method exists in Instrumentation but is hidden/not in public API.
         * We declare it here so our hooks can intercept it.
         */
        public ActivityResult execStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, Bundle options) {

            Log.d(TAG, "execStartActivity intercepted: " + intent);

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
                            Log.d(TAG, "Hook " + hook.getClass().getSimpleName() + " intercepted intent");
                            return result;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Hook " + hook.getClass().getSimpleName() + " threw exception", e);
                    }
                }
            }

            // No hook handled it, call original method
            return invokeOriginalExecStartActivity(who, contextThread, token, target, intent, requestCode, options);
        }

        /**
         * Invoke the original execStartActivity using reflection
         */
        private ActivityResult invokeOriginalExecStartActivity(
                Context who, IBinder contextThread, IBinder token, Activity target,
                Intent intent, int requestCode, Bundle options) {
            
            if (mExecStartActivityMethod == null) {
                Log.e(TAG, "execStartActivity method not available");
                return null;
            }

            try {
                Object result = mExecStartActivityMethod.invoke(
                    mBase, who, contextThread, token, target, intent, requestCode, options
                );
                return (ActivityResult) result;
            } catch (Exception e) {
                Log.e(TAG, "Failed to invoke original execStartActivity", e);
                return null;
            }
        }

        // Delegate all other methods to base
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

        @Override
        public void callActivityOnRestart(Activity activity) {
            mBase.callActivityOnRestart(activity);
        }
    }
}
