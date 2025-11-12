package com.applisto.appcloner;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public final class FakeCameraAppSupport extends ExecStartActivityHook {
    private static final String TAG = "FakeCameraAppSupport";

    // Runtime state for returning a result to the original caller
    private static WeakReference<Activity> sActivityRef;
    private static int sRequestCode = -1;
    private static Uri sUri;
    private static String sCameraMode; // "selfie", "front", "back"
    private static boolean sIsVideoCapture;

    private static FakeCameraAppSupport INSTANCE;
    private static Context sAppContext;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    // Synchronized lock for thread safety
    private static final Object LOCK = new Object();

    /** Call this once early (e.g., Application.onCreate). */
    public static void setup(Context ctx) {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                Log.d(TAG, "FakeCameraAppSupport already setup");
                return;
            }

            sAppContext = ctx.getApplicationContext();
            INSTANCE = new FakeCameraAppSupport();
            register(INSTANCE);

            Log.i(TAG, "FakeCameraAppSupport registered successfully");
        }
    }

    @Override
    protected boolean handle(
            Context who,
            IBinder contextThread,
            IBinder token,
            Activity target,
            Intent intent,
            int requestCode,
            Bundle options
    ) {
        if (intent == null) {
            return false;
        }

        String action = intent.getAction();

        // Check if this is a camera intent
        boolean isCameraCapture = MediaStore.ACTION_IMAGE_CAPTURE.equals(action);
        boolean isVideoCapture = MediaStore.ACTION_VIDEO_CAPTURE.equals(action);

        if (!isCameraCapture && !isVideoCapture) {
            return false;
        }

        // Only intercept if we're in an Activity context
        Activity activity = findActivity(who, target);
        if (activity == null) {
            Log.w(TAG, "No activity found for camera intent, not intercepting");
            return false;
        }

        Log.i(TAG, "Intercepting camera intent: " + action + " from " + activity.getClass().getSimpleName());

        synchronized (LOCK) {
            // Store original caller information
            sActivityRef = new WeakReference<>(activity);
            sRequestCode = requestCode;
            sUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
            sIsVideoCapture = isVideoCapture;

            // Extract camera mode hints if present
            sCameraMode = extractCameraMode(intent);
        }

        // Launch our FakeCameraActivity instead
        Intent fakeCameraIntent = new Intent(activity, FakeCameraActivity.class);
        fakeCameraIntent.putExtra("fake_camera_app", true);
        fakeCameraIntent.putExtra("is_video_capture", isVideoCapture);

        if (sUri != null) {
            fakeCameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, sUri);
        }

        if (sCameraMode != null) {
            fakeCameraIntent.putExtra("camera_mode", sCameraMode);
        }

        try {
            activity.startActivityForResult(fakeCameraIntent, requestCode);
            Log.d(TAG, "Launched FakeCameraActivity successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch FakeCameraActivity", e);
            synchronized (LOCK) {
                clearState();
            }
            return false;
        }

        // Return true to indicate we handled this intent
        return true;
    }

    /**
     * Extract camera mode from intent extras/flags
     */
    private String extractCameraMode(Intent intent) {
        // Check for common camera mode indicators
        if (intent.hasExtra("android.intent.extras.CAMERA_FACING")) {
            int facing = intent.getIntExtra("android.intent.extras.CAMERA_FACING", -1);
            if (facing == 1) return "front";
            if (facing == 0) return "back";
        }

        if (intent.hasExtra("android.intent.extra.USE_FRONT_CAMERA")) {
            return intent.getBooleanExtra("android.intent.extra.USE_FRONT_CAMERA", false)
                ? "front" : "back";
        }

        // Default to back camera
        return "back";
    }

    /**
     * Try to find the Activity context from various sources
     */
    private Activity findActivity(Context who, Activity target) {
        if (target != null) {
            return target;
        }

        if (who instanceof Activity) {
            return (Activity) who;
        }

        // Try to extract from context
        Context ctx = who;
        while (ctx != null) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            if (ctx instanceof android.content.ContextWrapper) {
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            } else {
                break;
            }
        }

        return null;
    }

    /**
     * Called by FakeCameraActivity when user picks/captures an image
     */
    public static void onImageSelected(final Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "onImageSelected: bitmap is null");
            deliverCancelResult();
            return;
        }

        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (LOCK) {
                        Activity activity = sActivityRef != null ? sActivityRef.get() : null;

                        if (activity == null || activity.isFinishing()) {
                            Log.w(TAG, "Activity is gone, cannot deliver result");
                            clearState();
                            return;
                        }

                        // Apply any transformations from CameraHook
                        Bitmap finalBitmap = applyTransformations(bitmap);

                        // Deliver the result
                        if (sUri != null) {
                            // Save to specified URI
                            saveBitmapToUri(activity, finalBitmap, sUri);
                            deliverSuccessResult(null);
                        } else {
                            // Return as thumbnail in extras
                            deliverSuccessResult(finalBitmap);
                        }

                        clearState();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error delivering image result", e);
                    deliverCancelResult();
                }
            }
        });
    }

    /**
     * Apply transformations from CameraHook (rotation, flip, etc.)
     */
    private static Bitmap applyTransformations(Bitmap original) {
        try {
            // Get transformation matrix from CameraHook
            Matrix matrix = CameraHook.getTransformationMatrix();
            if (matrix == null || matrix.isIdentity()) {
                return original;
            }

            Bitmap transformed = Bitmap.createBitmap(
                original, 0, 0,
                original.getWidth(),
                original.getHeight(),
                matrix,
                true
            );

            if (transformed != original) {
                original.recycle();
            }

            return transformed;
        } catch (Exception e) {
            Log.e(TAG, "Error applying transformations", e);
            return original;
        }
    }

    /**
     * Save bitmap to the specified URI
     */
    private static void saveBitmapToUri(Context ctx, Bitmap bitmap, Uri uri) {
        OutputStream out = null;
        try {
            out = ctx.getContentResolver().openOutputStream(uri);
            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
                Log.d(TAG, "Saved bitmap to URI: " + uri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bitmap to URI: " + uri, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Deliver success result to calling activity
     */
    private static void deliverSuccessResult(Bitmap thumbnail) {
        synchronized (LOCK) {
            Activity activity = sActivityRef != null ? sActivityRef.get() : null;
            if (activity == null) return;

            Intent resultIntent = new Intent();

            if (thumbnail != null) {
                // Add thumbnail to extras
                resultIntent.putExtra("data", thumbnail);
            }

            if (sUri != null) {
                resultIntent.setData(sUri);
            }

            // Use reflection to call setResult and finish
            try {
                Method setResult = Activity.class.getMethod("setResult", int.class, Intent.class);
                setResult.invoke(activity, Activity.RESULT_OK, resultIntent);

                Log.i(TAG, "Delivered RESULT_OK to activity");
            } catch (Exception e) {
                Log.e(TAG, "Failed to deliver result via reflection", e);
                // Fallback: try direct call
                try {
                    activity.setResult(Activity.RESULT_OK, resultIntent);
                } catch (Exception e2) {
                    Log.e(TAG, "Fallback setResult also failed", e2);
                }
            }
        }
    }

    /**
     * Deliver cancel result to calling activity
     */
    private static void deliverCancelResult() {
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    Activity activity = sActivityRef != null ? sActivityRef.get() : null;
                    if (activity == null || activity.isFinishing()) {
                        clearState();
                        return;
                    }

                    try {
                        activity.setResult(Activity.RESULT_CANCELED);
                        Log.i(TAG, "Delivered RESULT_CANCELED to activity");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to deliver cancel result", e);
                    }

                    clearState();
                }
            }
        });
    }

    /**
     * Clear stored state
     */
    private static void clearState() {
        sActivityRef = null;
        sRequestCode = -1;
        sUri = null;
        sCameraMode = null;
        sIsVideoCapture = false;
    }

    /**
     * Get stored request code
     */
    public static int getStoredRequestCode() {
        synchronized (LOCK) {
            return sRequestCode;
        }
    }

    /**
     * Get stored URI
     */
    public static Uri getStoredUri() {
        synchronized (LOCK) {
            return sUri;
        }
    }

    /**
     * Get stored camera mode
     */
    public static String getStoredCameraMode() {
        synchronized (LOCK) {
            return sCameraMode;
        }
    }

    /**
     * Check if current operation is video capture
     */
    public static boolean isVideoCapture() {
        synchronized (LOCK) {
            return sIsVideoCapture;
        }
    }
}
