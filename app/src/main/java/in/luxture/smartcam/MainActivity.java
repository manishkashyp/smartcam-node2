package in.luxture.smartcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private TextView statusTv, resTv, trackTv;
    private ImageView livePreviewIv;
    private EditText camIdInput, camNameInput, ownerIdInput, streamUrlInput, secretInput, smbIpInput;
    private Spinner sourceSpinner;
    private CheckBox humanCheck, animalCheck;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean previewActive = true;

    private static final String[] SOURCE_TYPES = {"phone_cam", "ip_snapshot", "rtsp_bridge"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        scroll.setBackgroundColor(Color.parseColor("#05080F"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🛡️ SmartCam Pro (Enterprise Node)");
        title.setTextColor(Color.parseColor("#00E676"));
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("QR Pairing · After-Market IP/RTSP · Human & Animal Tracking · Screen-Off 24x7");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        // QR Scan & JSON Paste Row
        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setOrientation(LinearLayout.HORIZONTAL);

        Button scanQrBtn = new Button(this);
        scanQrBtn.setText("📷 Scan Pairing QR");
        scanQrBtn.setBackgroundColor(Color.parseColor("#00C864"));
        scanQrBtn.setTextColor(Color.BLACK);
        scanQrBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        qrLp.setMargins(0, 0, dp(6), dp(10));
        scanQrBtn.setLayoutParams(qrLp);
        scanQrBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new IntentIntegrator(MainActivity.this)
                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                        .setPrompt("Scan SmartCam Pairing QR")
                        .setBeepEnabled(true)
                        .initiateScan();
            }
        });

        Button pasteQrBtn = new Button(this);
        pasteQrBtn.setText("📋 Paste QR JSON");
        pasteQrBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        pasteQrBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pasteLp.setMargins(dp(6), 0, 0, dp(10));
        pasteQrBtn.setLayoutParams(pasteLp);
        pasteQrBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrJsonDialog();
            }
        });

        qrRow.addView(scanQrBtn);
        qrRow.addView(pasteQrBtn);
        root.addView(qrRow);

        // Status Card
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setBackgroundColor(Color.parseColor("#101827"));
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

        trackTv = new TextView(this);
        trackTv.setText("AI Tracker: Monitoring...");
        trackTv.setTextColor(Color.parseColor("#00E676"));
        trackTv.setTextSize(12);
        statusBox.addView(trackTv);
        root.addView(statusBox);

        // Live Preview with Tracking Box Overlay
        livePreviewIv = new ImageView(this);
        LinearLayout.LayoutParams ivParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(215));
        ivParams.setMargins(0, dp(10), 0, dp(10));
        livePreviewIv.setLayoutParams(ivParams);
        livePreviewIv.setBackgroundColor(Color.BLACK);
        livePreviewIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(livePreviewIv);

        // Hardware Controls
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

        SharedPreferences prefs = getSharedPreferences("SmartCamPrefs", MODE_PRIVATE);

        // Human & Animal Detection Checkboxes
        humanCheck = new CheckBox(this);
        humanCheck.setText("🧍 Human Detection & Tracking");
        humanCheck.setTextColor(Color.WHITE);
        humanCheck.setChecked(prefs.getBoolean("detect_human", true));
        root.addView(humanCheck);

        animalCheck = new CheckBox(this);
        animalCheck.setText("🐾 Animal Detection & Tracking");
        animalCheck.setTextColor(Color.WHITE);
        animalCheck.setChecked(prefs.getBoolean("detect_animal", true));
        root.addView(animalCheck);

        root.addView(makeLabel("Customer Owner ID (e.g. 2):"));
        ownerIdInput = makeInput(prefs.getString("owner_id", ""));
        root.addView(ownerIdInput);

        root.addView(makeLabel("Camera Unique ID:"));
        camIdInput = makeInput(prefs.getString("cam_id", "cam_1"));
        root.addView(camIdInput);

        root.addView(makeLabel("Camera Location Name:"));
        camNameInput = makeInput(prefs.getString("cam_name", "Camera 1 (Hall)"));
        root.addView(camNameInput);

        root.addView(makeLabel("Camera Source Type (Phone / IP Snapshot / RTSP Bridge):"));
        sourceSpinner = new Spinner(this);
        sourceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SOURCE_TYPES));
        sourceSpinner.setSelection(prefs.getInt("source_idx", 0));
        root.addView(sourceSpinner);

        root.addView(makeLabel("After-Market Snapshot / RTSP URL (Optional):"));
        streamUrlInput = makeInput(prefs.getString("stream_url", ""));
        root.addView(streamUrlInput);

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
                persistAllFields();
                sendServiceCommand("RESTART_STREAM");
                Toast.makeText(MainActivity.this, "Saved! Now you can lock screen or keep streaming.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(saveBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void persistAllFields() {
        SharedPreferences.Editor ed = getSharedPreferences("SmartCamPrefs", MODE_PRIVATE).edit();
        ed.putString("owner_id", ownerIdInput.getText().toString().trim());
        ed.putString("cam_id", camIdInput.getText().toString().trim());
        ed.putString("cam_name", camNameInput.getText().toString().trim());
        ed.putInt("source_idx", sourceSpinner.getSelectedItemPosition());
        ed.putString("source_type", SOURCE_TYPES[sourceSpinner.getSelectedItemPosition()]);
        ed.putString("stream_url", streamUrlInput.getText().toString().trim());
        ed.putString("secret", secretInput.getText().toString().trim());
        ed.putString("smb_ip", smbIpInput.getText().toString().trim());
        ed.putBoolean("detect_human", humanCheck.isChecked());
        ed.putBoolean("detect_animal", animalCheck.isChecked());
        ed.apply();
    }

    private void applyQrJsonString(String raw) {
        try {
            JSONObject obj = new JSONObject(raw.trim());
            if (obj.has("owner_id") && !obj.isNull("owner_id")) {
                ownerIdInput.setText(String.valueOf(obj.optInt("owner_id", 0)));
            }
            if (obj.has("cam_id")) camIdInput.setText(obj.getString("cam_id"));
            if (obj.has("cam_name")) camNameInput.setText(obj.getString("cam_name"));
            if (obj.has("secret")) secretInput.setText(obj.getString("secret"));
            if (obj.has("stream_url")) streamUrlInput.setText(obj.getString("stream_url"));
            if (obj.has("source_type")) {
                String st = obj.getString("source_type");
                for (int i = 0; i < SOURCE_TYPES.length; i++) {
                    if (SOURCE_TYPES[i].equals(st)) {
                        sourceSpinner.setSelection(i);
                        break;
                    }
                }
            }
            if (obj.has("api_url")) {
                getSharedPreferences("SmartCamPrefs", MODE_PRIVATE)
                        .edit().putString("server_url", obj.getString("api_url")).apply();
            }
            persistAllFields();
            sendServiceCommand("RESTART_STREAM");
            Toast.makeText(this, "QR Paired Successfully!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Invalid QR payload", Toast.LENGTH_SHORT).show();
        }
    }

    private void showQrJsonDialog() {
        final EditText box = new EditText(this);
        box.setHint("{\"cam_id\":\"cam_shop_1\",\"cam_name\":\"Shop\",\"owner_id\":2,\"secret\":\"LUX2026\"}");
        new AlertDialog.Builder(this)
                .setTitle("Paste Pairing QR JSON")
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> applyQrJsonString(box.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            applyQrJsonString(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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
        b.setOnClickListener(v -> sendServiceCommand(actionCmd));
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
        et.setBackgroundColor(Color.parseColor("#162033"));
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
                    trackTv.setText("AI Tracker: " + NativeCameraService.lastTrackEvent);

                    byte[] jpeg = NativeCameraService.latestJpegFrame;
                    if (jpeg != null && jpeg.length > 0) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (bmp != null) {
                            Rect box = NativeCameraService.trackedBox;
                            if (box != null && !"NONE".equals(NativeCameraService.lastTrackEvent)) {
                                Bitmap mutable = bmp.copy(Bitmap.Config.ARGB_8888, true);
                                Canvas canvas = new Canvas(mutable);
                                Paint p = new Paint();
                                p.setColor(Color.parseColor("#00E676"));
                                p.setStyle(Paint.Style.STROKE);
                                p.setStrokeWidth(4f);
                                canvas.drawRect(box, p);
                                p.setStyle(Paint.Style.FILL);
                                p.setTextSize(22f);
                                canvas.drawText(NativeCameraService.lastTrackEvent, box.left, Math.max(26f, box.top - 6f), p);
                                bmp.recycle();
                                bmp = mutable;
                            }
                            livePreviewIv.setImageBitmap(bmp);
                        }
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
