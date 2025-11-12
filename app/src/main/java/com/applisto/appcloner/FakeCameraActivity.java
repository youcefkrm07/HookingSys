package com.applisto.appcloner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class FakeCameraActivity extends Activity {
    private static final String TAG = "FakeCameraActivity";
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int MAX_IMAGE_SIZE = 2048; // Max dimension

    private String cameraMode;
    private boolean usedOpenDocument;
    private boolean isFakeCameraAppMode;
    private boolean isVideoCapture;
    private Uri outputUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure CameraHook is installed
        try {
            CameraHook.install(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to install CameraHook", e);
        }

        // Parse intent extras
        Intent intent = getIntent();
        if (intent != null) {
            isFakeCameraAppMode = intent.getBooleanExtra("fake_camera_app", false);
            isVideoCapture = intent.getBooleanExtra("is_video_capture", false);
            cameraMode = intent.getStringExtra("camera_mode");
            outputUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);

            Log.d(TAG, "Started with mode=" + cameraMode + ", video=" + isVideoCapture + ", uri=" + outputUri);
        }

        if (isVideoCapture) {
            // For now, video capture is not supported
            Toast.makeText(this, "Video capture not yet supported", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // Launch image picker
        launchImagePicker();
    }

    /**
     * Launch the appropriate image picker
     */
    private void launchImagePicker() {
        try {
            Intent pickIntent;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // Use ACTION_OPEN_DOCUMENT for newer Android versions
                pickIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                pickIntent.setType("image/*");
                usedOpenDocument = true;
            } else {
                // Fallback to ACTION_PICK for older versions
                pickIntent = new Intent(Intent.ACTION_PICK);
                pickIntent.setType("image/*");
                pickIntent.setData(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                usedOpenDocument = false;
            }

            // Add hint for camera mode if available
            if (cameraMode != null) {
                pickIntent.putExtra("camera_mode_hint", cameraMode);
            }

            startActivityForResult(pickIntent, REQ_PICK_IMAGE);
            Log.d(TAG, "Launched image picker (openDocument=" + usedOpenDocument + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to launch image picker", e);
            Toast.makeText(this, "Failed to open image picker", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d(TAG, "onActivityResult: request=" + requestCode + ", result=" + resultCode);

        if (requestCode != REQ_PICK_IMAGE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "User cancelled or no data returned");

            if (isFakeCameraAppMode) {
                FakeCameraAppSupport.onImageSelected(null);
            } else {
                setResult(RESULT_CANCELED);
            }
            finish();
            return;
        }

        // Process selected image in background
        final Uri selectedUri = data.getData();
        if (selectedUri == null) {
            Log.e(TAG, "No URI in result data");
            handleError("No image selected");
            return;
        }

        Log.i(TAG, "Image selected: " + selectedUri);

        // Process image in background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                processSelectedImage(selectedUri);
            }
        }).start();
    }

    /**
     * Process the selected image (load, transform, deliver)
     */
    private void processSelectedImage(Uri uri) {
        Bitmap bitmap = null;

        try {
            // Load bitmap from URI
            bitmap = loadBitmapFromUri(uri);

            if (bitmap == null) {
                handleError("Failed to load image");
                return;
            }

            Log.d(TAG, "Loaded bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Scale down if too large
            bitmap = scaleBitmapIfNeeded(bitmap);

            // Apply transformations from CameraHook
            bitmap = applyHookTransformations(bitmap);

            // Deliver result
            if (isFakeCameraAppMode) {
                // Let FakeCameraAppSupport handle delivery
                FakeCameraAppSupport.onImageSelected(bitmap);
                finishOnUiThread();
            } else {
                // Direct mode: deliver result ourselves
                deliverResult(bitmap);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            handleError("Error processing image: " + e.getMessage());
        } finally {
            // Don't recycle here if we passed it to FakeCameraAppSupport
            if (bitmap != null && !isFakeCameraAppMode) {
                bitmap.recycle();
            }
        }
    }

    /**
     * Load bitmap from URI with proper error handling
     */
    private Bitmap loadBitmapFromUri(Uri uri) {
        InputStream input = null;
        try {
            input = getContentResolver().openInputStream(uri);
            if (input == null) {
                Log.e(TAG, "Failed to open input stream for URI: " + uri);
                return null;
            }

            // Decode bitmap
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);

            if (bitmap == null) {
                Log.e(TAG, "BitmapFactory returned null for URI: " + uri);
            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Exception loading bitmap from URI: " + uri, e);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Scale bitmap if it exceeds maximum dimensions
     */
    private Bitmap scaleBitmapIfNeeded(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
            return original;
        }

        float scale = Math.min(
            (float) MAX_IMAGE_SIZE / width,
            (float) MAX_IMAGE_SIZE / height
        );

        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        Log.d(TAG, "Scaling bitmap from " + width + "x" + height + " to " + newWidth + "x" + newHeight);

        Bitmap scaled = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);

        if (scaled != original) {
            original.recycle();
        }

        return scaled;
    }

    /**
     * Apply transformations from CameraHook
     */
    private Bitmap applyHookTransformations(Bitmap original) {
        try {
            Matrix matrix = CameraHook.getTransformationMatrix();

            if (matrix == null || matrix.isIdentity()) {
                return original;
            }

            Log.d(TAG, "Applying CameraHook transformations");

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
     * Deliver result in direct mode (not using FakeCameraAppSupport)
     */
    private void deliverResult(final Bitmap bitmap) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent resultIntent = new Intent();

                    if (outputUri != null) {
                        // Save to specified URI
                        saveBitmapToUri(bitmap, outputUri);
                        resultIntent.setData(outputUri);
                    } else {
                        // Return as thumbnail
                        resultIntent.putExtra("data", bitmap);
                    }

                    setResult(RESULT_OK, resultIntent);
                    Log.i(TAG, "Result delivered successfully");

                } catch (Exception e) {
                    Log.e(TAG, "Error delivering result", e);
                    setResult(RESULT_CANCELED);
                } finally {
                    finish();
                }
            }
        });
    }

    /**
     * Save bitmap to URI
     */
    private void saveBitmapToUri(Bitmap bitmap, Uri uri) {
        try {
            java.io.OutputStream out = getContentResolver().openOutputStream(uri);
            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
                out.close();
                Log.d(TAG, "Saved bitmap to URI: " + uri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bitmap to URI", e);
            throw new RuntimeException("Failed to save image", e);
        }
    }

    /**
     * Handle errors by showing toast and finishing
     */
    private void handleError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FakeCameraActivity.this, message, Toast.LENGTH_SHORT).show();

                if (isFakeCameraAppMode) {
                    FakeCameraAppSupport.onImageSelected(null);
                } else {
                    setResult(RESULT_CANCELED);
                }

                finish();
            }
        });
    }

    /**
     * Finish on UI thread
     */
    private void finishOnUiThread() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Activity destroyed");
    }
}
