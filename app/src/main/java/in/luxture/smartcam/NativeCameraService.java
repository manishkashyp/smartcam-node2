package in.luxture.smartcam;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Base64;
import android.util.Size;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class NativeCameraService extends Service {

    public static volatile byte[] latestJpegFrame = null;
    public static volatile String currentStatus = "Initializing Native Camera2...";
    public static volatile String maxSensorResolution = "Detecting...";
    public static volatile String localIpUrl = "http://0.0.0.0:8080";
    public static volatile int rotationDegrees = 0; // 0, 90, 180, 270
    public static volatile boolean torchEnabled = false;
    public static volatile boolean useFrontCamera = false;
    public static volatile float zoomLevel = 1.0f;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CaptureRequest.Builder previewRequestBuilder;
    private String activeCameraId;
    private Rect sensorArraySize;

    private LocalHttpServer httpServer;
    private ScheduledExecutorService cloudScheduler;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private String lastLat = "26.4499";
    private String lastLng = "74.6399";

    @Override
    public void onCreate() {
        super.onCreate();
        acquireLocks();
        startBackgroundThread();
        setupLocation();

        try {
            httpServer = new LocalHttpServer(8080);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            localIpUrl = "http://" + getLocalIpAddress() + ":8080";
        } catch (Exception e) {
            currentStatus = "Port 8080 Error: " + e.getMessage();
        }

        openNativeCamera();
        startCloudSyncLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleCommand(intent.getAction());
        }
        startForegroundNotification();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "smartcam_native_service";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "SmartCam Native Camera2 Engine",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("SmartCam Native Server Active")
                .setContentText("Local: " + localIpUrl + " | Screen-Off Ready")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(101, notification);
    }

    @SuppressLint("MissingPermission")
    public synchronized void openNativeCamera() {
        closeCamera();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) return;

        try {
            String chosenId = null;
            int targetLens = useFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;

            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == targetLens) {
                    chosenId = id;
                    break;
                }
            }
            if (chosenId == null && cameraManager.getCameraIdList().length > 0) {
                chosenId = cameraManager.getCameraIdList()[0];
            }
            activeCameraId = chosenId;

            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(activeCameraId);
            sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                if (jpegSizes != null && jpegSizes.length > 0) {
                    Size max = jpegSizes[0];
                    for (Size s : jpegSizes) {
                        if ((long) s.getWidth() * s.getHeight() > (long) max.getWidth() * max.getHeight()) {
                            max = s;
                        }
                    }
                    int mp = Math.round((max.getWidth() * max.getHeight()) / 1000000.0f);
                    maxSensorResolution = max.getWidth() + "x" + max.getHeight() + " (" + mp + "MP Sensor)";
                }
            }

            // Stream in clean Landscape 16:9 HD (1280x720) for zero-lag real-time feed + rotation control
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            byte[] nv21 = yuv420ToNv21(image);
                            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 75, out);
                            byte[] rawJpeg = out.toByteArray();

                            if (rotationDegrees != 0) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.length);
                                if (bmp != null) {
                                    Matrix matrix = new Matrix();
                                    matrix.postRotate(rotationDegrees);
                                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                                    ByteArrayOutputStream rotOut = new ByteArrayOutputStream();
                                    rotated.compress(Bitmap.CompressFormat.JPEG, 75, rotOut);
                                    latestJpegFrame = rotOut.toByteArray();
                                    bmp.recycle();
                                    rotated.recycle();
                                }
                            } else {
                                latestJpegFrame = rawJpeg;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (image != null) image.close();
                    }
                }
            }, backgroundHandler);

            cameraManager.openCamera(activeCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession();
                    currentStatus = "ONLINE & STREAMING (" + localIpUrl + ")";
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    currentStatus = "Camera Disconnected - Retrying...";
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    currentStatus = "Camera Error " + error + " - Auto-Healing...";
                }
            }, backgroundHandler);

        } catch (Exception e) {
            currentStatus = "Camera Init Error: " + e.getMessage();
        }
    }

    private void createCameraPreviewSession() {
        try {
            if (cameraDevice == null || imageReader == null) return;
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(imageReader.getSurface());
            applyCaptureSettings();

            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException ignored) {}
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            currentStatus = "Session Config Failed";
                        }
                    }, backgroundHandler);
        } catch (Exception ignored) {}
    }

    private void applyCaptureSettings() {
        if (previewRequestBuilder == null) return;
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                torchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

        if (sensorArraySize != null && zoomLevel > 1.0f) {
            int centerX = sensorArraySize.width() / 2;
            int centerY = sensorArraySize.height() / 2;
            int deltaX = (int) ((0.5f * sensorArraySize.width()) / zoomLevel);
            int deltaY = (int) ((0.5f * sensorArraySize.height()) / zoomLevel);
            Rect crop = new Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY);
            previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop);
        } else if (sensorArraySize != null) {
            previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorArraySize);
        }

        if (captureSession != null) {
            try {
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
            } catch (Exception ignored) {}
        }
    }

    public void handleCommand(String cmd) {
        if ("TORCH_TOGGLE".equals(cmd)) {
            torchEnabled = !torchEnabled;
            applyCaptureSettings();
        } else if ("SWITCH_CAM".equals(cmd)) {
            useFrontCamera = !useFrontCamera;
            torchEnabled = false;
            openNativeCamera();
        } else if ("ROTATE_90".equals(cmd)) {
            rotationDegrees = (rotationDegrees + 90) % 360;
        } else if ("ZOOM_2X".equals(cmd)) {
            zoomLevel = 2.0f;
            applyCaptureSettings();
        } else if ("ZOOM_1X".equals(cmd)) {
            zoomLevel = 1.0f;
            applyCaptureSettings();
        } else if ("SIREN_ON".equals(cmd)) {
            playSirenTone();
        } else if ("RESTART_STREAM".equals(cmd)) {
            openNativeCamera();
        }
    }

    private void playSirenTone() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int sampleRate = 8000;
                    int numSamples = sampleRate * 3;
                    byte[] generatedSnd = new byte[2 * numSamples];
                    for (int i = 0; i < numSamples; ++i) {
                        double freq = (i % 4000 < 2000) ? 880 : 1200;
                        short val = (short) (Math.sin(2 * Math.PI * i / (sampleRate / freq)) * 32767);
                        generatedSnd[2 * i] = (byte) (val & 0x00ff);
                        generatedSnd[2 * i + 1] = (byte) ((val & 0xff00) >>> 8);
                    }
                    AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_ALARM,
                            sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, generatedSnd.length,
                            AudioTrack.MODE_STATIC);
                    audioTrack.write(generatedSnd, 0, generatedSnd.length);
                    audioTrack.play();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // Embedded Local HTTP Server (Port 8080 - Exact IP Webcam style + MJPEG live stream)
    private class LocalHttpServer extends NanoHTTPD {
        public LocalHttpServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Map<String, String> params = session.getParms();

            if ("/shot.jpg".equals(uri)) {
                byte[] frame = latestJpegFrame;
                if (frame == null) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No frame");
                Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Cache-Control", "no-store");
                return r;
            }

            if ("/cmd".equals(uri)) {
                String action = params.get("action");
                if (action != null) handleCommand(action);
                Response r = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\",\"rotation\":" + rotationDegrees + "}");
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            }

            // Local Built-in Control Panel at http://<Phone-IP>:8080
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>SmartCam Native Local Server</title>" +
                    "<style>body{background:#0b0f19;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:15px}" +
                    "img{max-width:100%;height:auto;border:2px solid #00e676;border-radius:8px;background:#000}" +
                    "button{padding:10px 14px;margin:5px;border:none;border-radius:6px;background:#2979ff;color:#fff;font-weight:bold;cursor:pointer}</style></head><body>" +
                    "<h3>SmartCam Native Hardware Server (" + maxSensorResolution + ")</h3>" +
                    "<div><img id='live' src='/shot.jpg'></div>" +
                    "<div style='margin-top:12px'>" +
                    "<button onclick="cmd('ROTATE_90')">🔄 Rotate 90° (Fix Vertical/Horizontal)</button>" +
                    "<button onclick="cmd('TORCH_TOGGLE')">🔦 Torch ON/OFF</button>" +
                    "<button onclick="cmd('SWITCH_CAM')">📷 Flip Front/Back</button>" +
                    "<button onclick="cmd('ZOOM_2X')">🔍 Zoom 2x</button>" +
                    "<button onclick="cmd('ZOOM_1X')">🔍 Zoom 1x</button>" +
                    "<button onclick="cmd('SIREN_ON')" style='background:#d50000'>🚨 Siren</button>" +
                    "</div>" +
                    "<script>setInterval(()=>{document.getElementById('live').src='/shot.jpg?t='+Date.now()},200);" +
                    "function cmd(a){fetch('/cmd?action='+a)}</script></body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html", html);
        }
    }

    // Cloud Sync Loop -> Sends Live Frame + Telemetry to vastram.luxture.in & executes Instant Commands
    private void startCloudSyncLoop() {
        cloudScheduler = Executors.newSingleThreadScheduledExecutor();
        cloudScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = getSharedPreferences("SmartCamPrefs", MODE_PRIVATE);
                    String serverUrl = prefs.getString("server_url", "https://vastram.luxture.in/cctv/api.php");
                    String camId = prefs.getString("cam_id", "cam_1");
                    String camName = prefs.getString("cam_name", "Camera 1 (Native)");
                    String secret = prefs.getString("secret", "LUX2026");

                    Intent batIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int level = 100, charging = 1;
                    float tempC = 32.0f;
                    if (batIntent != null) {
                        int rawLevel = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        if (rawLevel >= 0 && scale > 0) level = (rawLevel * 100) / scale;
                        int status = batIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        charging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;
                        tempC = batIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) / 10.0f;
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("action", "heartbeat");
                    payload.put("secret", secret);
                    payload.put("cam_id", camId);
                    payload.put("cam_name", camName);
                    payload.put("peer_id", localIpUrl);
                    payload.put("resolution", maxSensorResolution + " [" + rotationDegrees + "°]");
                    payload.put("battery", level);
                    payload.put("charging", charging);
                    payload.put("temp", tempC);
                    payload.put("ssd_free", getFreeStorageGb());
                    payload.put("lat", lastLat);
                    payload.put("lng", lastLng);

                    if (latestJpegFrame != null) {
                        payload.put("frame_b64", Base64.encodeToString(latestJpegFrame, Base64.NO_WRAP));
                    }

                    URL url = new URL(serverUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setDoOutput(true);

                    byte[] outBytes = payload.toString().getBytes("UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(outBytes);
                    os.close();

                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    is.close();

                    JSONObject resJson = new JSONObject(baos.toString("UTF-8"));
                    if (resJson.has("execute_cmd") && !resJson.isNull("execute_cmd")) {
                        String cmd = resJson.getString("execute_cmd");
                        if (cmd != null && !cmd.isEmpty() && !"null".equals(cmd)) {
                            handleCommand(cmd);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, 1000, 1200, TimeUnit.MILLISECONDS);
    }

    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int rowStride = image.getPlanes()[0].getRowStride();
        int pos = 0;
        if (rowStride == width) {
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        } else {
            long yBufferPos = -rowStride;
            for (; pos < ySize; pos += width) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }
        return nv21;
    }

    private float getFreeStorageGb() {
        try {
            File path = Environment.getExternalStorageDirectory();
            StatFs stat = new StatFs(path.getPath());
            long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
            return Math.round((bytesAvailable / (1024.0f * 1024.0f * 1024.0f)) * 10.0f) / 10.0f;
        } catch (Exception e) {
            return 0.0f;
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wifiInfo = wm.getConnectionInfo();
                int ip = wifiInfo.getIpAddress();
                return String.format("%d.%d.%d.%d",
                        (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    @SuppressLint("MissingPermission")
    private void setupLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location loc) {
                        if (loc != null) {
                            lastLat = String.valueOf(loc.getLatitude());
                            lastLng = String.valueOf(loc.getLongitude());
                        }
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 5, listener);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 5, listener);
            }
        } catch (Exception ignored) {}
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCam::NativeCpuWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartCam::NativeWifiLock");
            wifiLock.acquire();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera2NativeThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private synchronized void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        closeCamera();
        if (httpServer != null) httpServer.stop();
        if (cloudScheduler != null) cloudScheduler.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
