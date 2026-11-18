package in.luxture.smartcam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusTv;
    private TextView resTv;
    private ImageView livePreviewIv;
    private EditText camIdInput, camNameInput, secretInput, smbIpInput;
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean previewActive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Allow unlocking over keyguard on reboot, but DO NOT force screen on (works with Power Button OFF!)
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );

        requestRequiredPermissions();
        buildNativeInterface();
        startNativeCameraEngine();
        startPreviewLoop();
    }

    private void buildNativeInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B0F19"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Header Title
        TextView title = new TextView(this);
        title.setText("🛡️ SmartCam Pro (100% Native Engine)");
        title.setTextColor(Color.parseColor("#00E676"));
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Power Button दबाकर Screen बंद (LOCK) करने पर भी कैमरा 24x7 बैकग्राउंड में चलता रहेगा!");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        // Status Card
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setBackgroundColor(Color.parseColor("#151C2C"));
        statusBox.setPadding(dp(12), dp(10), dp(12), dp(10));

        statusTv = new TextView(this);
        statusTv.setText("Status: Starting Native Camera2 Service...");
        statusTv.setTextColor(Color.parseColor("#FFEB3B"));
        statusTv.setTextSize(13);
        statusTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusBox.addView(statusTv);

        resTv = new TextView(this);
        resTv.setText("Hardware Sensor: Scanning...");
        resTv.setTextColor(Color.parseColor("#38BDF8"));
        resTv.setTextSize(12);
        statusBox.addView(resTv);
        root.addView(statusBox);

        // Live Native Preview ImageView
        livePreviewIv = new ImageView(this);
        LinearLayout.LayoutParams ivParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210));
        ivParams.setMargins(0, dp(10), 0, dp(10));
        livePreviewIv.setLayoutParams(ivParams);
        livePreviewIv.setBackgroundColor(Color.BLACK);
        livePreviewIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(livePreviewIv);

        // Quick Native Hardware Control Row
        LinearLayout ctrlRow1 = new LinearLayout(this);
        ctrlRow1.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow1.setGravity(Gravity.CENTER);

        ctrlRow1.addView(makeActionButton("🔄 Rotate 90°", "#2979FF", "ROTATE_90"));
        ctrlRow1.addView(makeActionButton("🔦 Torch", "#FF9100", "TORCH_TOGGLE"));
        ctrlRow1.addView(makeActionButton("📷 Flip Cam", "#7C4DFF", "SWITCH_CAM"));
        root.addView(ctrlRow1);

        LinearLayout ctrlRow2 = new LinearLayout(this);
        ctrlRow2.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow2.setGravity(Gravity.CENTER);
        ctrlRow2.addView(makeActionButton("🔍 Zoom 2x", "#1E293B", "ZOOM_2X"));
        ctrlRow2.addView(makeActionButton("🔍 Zoom 1x", "#1E293B", "ZOOM_1X"));
        ctrlRow2.addView(makeActionButton("🚨 Siren Test", "#D50000", "SIREN_ON"));
        root.addView(ctrlRow2);

        // Configuration Inputs
        SharedPreferences prefs = getSharedPreferences("SmartCamPrefs", MODE_PRIVATE);

        root.addView(makeLabel("Camera Unique ID:"));
        camIdInput = makeInput(prefs.getString("cam_id", "cam_1"));
        root.addView(camIdInput);

        root.addView(makeLabel("Camera Location Name:"));
        camNameInput = makeInput(prefs.getString("cam_name", "Camera 1 (Hall)"));
        root.addView(camNameInput);

        root.addView(makeLabel("Master Server API Secret:"));
        secretInput = makeInput(prefs.getString("secret", "LUX2026"));
        root.addView(secretInput);

        root.addView(makeLabel("JioFiber Router SMB/FTP IP (For SSD Sync):"));
        smbIpInput = makeInput(prefs.getString("smb_ip", "192.168.29.1"));
        root.addView(smbIpInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("💾 SAVE CONFIG & RESTART NATIVE ENGINE");
        saveBtn.setBackgroundColor(Color.parseColor("#00E676"));
        saveBtn.setTextColor(Color.BLACK);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.setMargins(0, dp(14), 0, dp(20));
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor ed = getSharedPreferences("SmartCamPrefs", MODE_PRIVATE).edit();
                ed.putString("cam_id", camIdInput.getText().toString().trim());
                ed.putString("cam_name", camNameInput.getText().toString().trim());
                ed.putString("secret", secretInput.getText().toString().trim());
                ed.putString("smb_ip", smbIpInput.getText().toString().trim());
                ed.apply();
                sendServiceCommand("RESTART_STREAM");
                Toast.makeText(MainActivity.this, "Saved! Now you can press Power Button to turn screen OFF.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(saveBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    private Button makeActionButton(String text, String colorHex, final String actionCmd) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendServiceCommand(actionCmd);
            }
        });
        return b;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CBD5E1"));
        tv.setTextSize(12);
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private EditText makeInput(String defaultVal) {
        EditText et = new EditText(this);
        et.setText(defaultVal);
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(Color.parseColor("#1E293B"));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        return et;
    }

    private void sendServiceCommand(String action) {
        Intent i = new Intent(this, NativeCameraService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void startNativeCameraEngine() {
        Intent serviceIntent = new Intent(this, NativeCameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void startPreviewLoop() {
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (previewActive) {
                    statusTv.setText(NativeCameraService.currentStatus);
                    resTv.setText("Max Sensor: " + NativeCameraService.maxSensorResolution +
                            " | Orientation: " + NativeCameraService.rotationDegrees + "°");
                    byte[] jpeg = NativeCameraService.latestJpegFrame;
                    if (jpeg != null && jpeg.length > 0) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (bmp != null) livePreviewIv.setImageBitmap(bmp);
                    }
                }
                uiHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    @Override
    protected void onResume() {
        super.onResume();
        previewActive = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop UI rendering when screen is OFF so battery/CPU is saved while NativeCameraService keeps running!
        previewActive = false;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            boolean needReq = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needReq = true;
            }
            if (needReq) requestPermissions(perms, 200);

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        sendServiceCommand("RESTART_STREAM");
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
