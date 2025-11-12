package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.io.File;

public class DefaultProvider extends AbstractContentProvider {

    private static final String TAG = "DefaultProvider";

    // Keep receiver reference to avoid GC
    private static DataExportReceiver sExportReceiver;

    // =============================
    // Broadcast Receiver for Data Export
    // =============================
    public static class DataExportReceiver extends BroadcastReceiver {
        public static final String ACTION_EXPORT_DATA = "com.applisto.appcloner.ACTION_EXPORT_DATA";

        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_EXPORT_DATA.equals(intent.getAction())) {
                Log.i(TAG, "Received export request");

                String pkg = ctx.getPackageName();
                if (pkg == null) {
                    Log.e(TAG, "Package name is null during export");
                    sendExportResult(ctx, pkg, false, null);
                    return;
                }

                try {
                    File exportedFile = new AppDataManager(ctx, pkg, false).exportAppData();
                    sendExportResult(ctx, pkg, exportedFile != null, exportedFile);
                } catch (Exception e) {
                    Log.e(TAG, "Export failed", e);
                    sendExportResult(ctx, pkg, false, null);
                }
            }
        }

        private void sendExportResult(Context ctx, String pkg, boolean success, File file) {
            Intent broadcast = new Intent("com.appcloner.replica.EXPORT_COMPLETED")
                    .setPackage("com.appcloner.replica")
                    .putExtra("exported_package", pkg)
                    .putExtra("export_success", success)
                    .putExtra("export_path", file == null ? null : file.getAbsolutePath());

            try {
                ctx.sendBroadcast(broadcast);
                Log.d(TAG, "Export result broadcast sent: success=" + success);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send export result broadcast", e);
            }
        }
    }

    // =============================
    // Android ContentProvider Lifecycle: onCreate()
    // =============================
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            Log.e(TAG, "Context is null in onCreate()");
            return false;
        }

        Log.i(TAG, "=== DEFAULT PROVIDER INITIALIZATION STARTED ===");
        Log.i(TAG, "Package: " + context.getPackageName());

        try {
            // 1. CRITICAL: EARLY CAMERA HOOK INSTALLATION - MUST BE FIRST
            Log.i(TAG, "Installing camera hooks...");
            CameraHook.install(context);
            Log.i(TAG, "Camera hooks installed successfully");

            // 2. Fake camera app support for activity interception
            Log.i(TAG, "Setting up FakeCameraAppSupport...");
            FakeCameraAppSupport.setup(context);
            Log.i(TAG, "FakeCameraAppSupport setup completed");

            // 3. Network proxy initialization
            Log.i(TAG, "Initializing network proxy...");
            Socks5ProxyHook.initEarly(context);
            Log.i(TAG, "Network proxy initialized");

            // 4. Data manager for import/export functionality
            Log.i(TAG, "Initializing AppDataManager...");
            new AppDataManager(context, context.getPackageName(), false);
            Log.i(TAG, "AppDataManager initialized");

            // 5. Register export broadcast receiver
            Log.i(TAG, "Registering export receiver...");
            if (sExportReceiver == null) {
                sExportReceiver = new DataExportReceiver();
            }
            IntentFilter filter = new IntentFilter(DataExportReceiver.ACTION_EXPORT_DATA);
            context.registerReceiver(sExportReceiver, filter);
            Log.i(TAG, "Export receiver registered");

            Log.i(TAG, "=== DEFAULT PROVIDER INITIALIZED SUCCESSFULLY ===");
            Log.i(TAG, "All hooks and services are now active");
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "=== CRITICAL FAILURE DURING PROVIDER INIT ===", t);
            return false;
        }
    }

    // Optional: Clean up on process death (if needed)
    @Override
    public void shutdown() {
        super.shutdown();
        Context context = getContext();
        if (context != null && sExportReceiver != null) {
            try {
                context.unregisterReceiver(sExportReceiver);
                Log.d(TAG, "Export receiver unregistered");
            } catch (Exception ignored) {}
        }
    }
}