package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class CameraControlReceiver extends BroadcastReceiver {

    private static final String TAG = "CameraControlReceiver";

    public static final String ACTION_ROTATE_CLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_CLOCKWISE";
    public static final String ACTION_ROTATE_COUNTERCLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_COUNTERCLOCKWISE";
    public static final String ACTION_FLIP_HORIZONTALLY = "com.applisto.appcloner.ACTION_FLIP_HORIZONTALLY";
    public static final String ACTION_FLIP_VERTICALLY = "com.applisto.appcloner.ACTION_FLIP_VERTICALLY";
    public static final String ACTION_RESET_TRANSFORMATIONS = "com.applisto.appcloner.ACTION_RESET_TRANSFORMATIONS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        try {
            // Ensure CameraHook is installed
            CameraHook.install(context.getApplicationContext());

            String action = intent.getAction();
            Log.i(TAG, "Received action: " + action);

            switch (action) {
                case ACTION_ROTATE_CLOCKWISE:
                    CameraHook.applyRotation(90);
                    showToast(context, "Rotated 90° clockwise");
                    break;

                case ACTION_ROTATE_COUNTERCLOCKWISE:
                    CameraHook.applyRotation(-90);
                    showToast(context, "Rotated 90° counter-clockwise");
                    break;

                case ACTION_FLIP_HORIZONTALLY:
                    CameraHook.applyFlip(true, false);
                    showToast(context, "Flipped horizontally");
                    break;

                case ACTION_FLIP_VERTICALLY:
                    CameraHook.applyFlip(false, true);
                    showToast(context, "Flipped vertically");
                    break;

                case ACTION_RESET_TRANSFORMATIONS:
                    CameraHook.resetTransformations();
                    showToast(context, "Reset transformations");
                    break;

                default:
                    Log.w(TAG, "Unknown action: " + action);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling action: " + intent.getAction(), e);
        }
    }

    private void showToast(final Context context, final String message) {
        // Post to main thread
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
