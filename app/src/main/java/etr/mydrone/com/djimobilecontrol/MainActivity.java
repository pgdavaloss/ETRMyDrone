package etr.mydrone.com.djimobilecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.MyLocationConfiguration;
import com.baidu.mapapi.map.MyLocationData;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.map.UiSettings;
import com.baidu.mapapi.model.LatLng;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import etr.mydrone.com.djimobilecontrol.ConverterUtil.CoordinationConverter;
import etr.mydrone.com.djimobilecontrol.ConverterUtil.LLDistanceConverter;
import etr.mydrone.com.djimobilecontrol.ConverterUtil.ScreenSizeConverter;
import etr.mydrone.com.djimobilecontrol.DataUtil.OnboardDataEncoder;
import etr.mydrone.com.djimobilecontrol.FlightModuleUtil.BatteryManager;
import etr.mydrone.com.djimobilecontrol.FlightModuleUtil.FlightControllerManager;
import etr.mydrone.com.djimobilecontrol.UIComponentUtil.RectView;
import etr.mydrone.com.djimobilecontrol.UIComponentUtil.SideToast;
import etr.mydrone.com.djimobilecontrol.UIComponentUtil.SimpleAlertDialog;
import etr.mydrone.com.djimobilecontrol.UIComponentUtil.SimpleDialogButton;
import etr.mydrone.com.djimobilecontrol.UIComponentUtil.SimpleProgressDialog;
import dji.common.battery.BatteryState;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.mission.waypoint.Waypoint;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.battery.Battery;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.sdkmanager.DJISDKManager;


public class MainActivity extends Activity {
    private final static String LINE_BROKER = "\r\n";
    private final AtomicInteger atomicInteger = new AtomicInteger();
    //// TODO: 2017/5/17 check static is necessary
    private BaseProduct baseProduct;
    /*
        UI components
     */
    private RelativeLayout relativeLayoutMain;
    private FrameLayout videoTextureViewFrameLayout;
    private ImageView remainingBatteryImageView;
    private ImageView cameraShootImageView;
    private ImageView cameraSwitchImageView;
    private TextView satelliteNumberTextView;
    private TextView statusVerticaLDistanceTextView;
    private TextView statusHorizontalDistanceTextView;
    private TextView statusHorizontalVelocityTextView;
    private TextView statusVerticalVelocityTextView;
    private Button mapPanelUndoButton;
    private Button mapPanelStartMissionButton;
    private Button mapPanelCancelMissionButton;
    private Button mapPanelStopMissionButton;
    private Switch[] developSwitchGroup;

    private SimpleProgressDialog startUpInfoDialog;
    private TextureView videoTextureView;
    private RectView rectView;
    private ScreenSizeConverter screenSizeConverter;
    /*
        baidu map
     */
    private LinearLayout linearLayoutForMap;
    private MapView mapView;
    private RelativeLayout mapViewPanel;
    private BaiduMap baiduMap;
    /*
        data
     */
    private float waypointMissionFlightHeight = 30.0f;
    private byte[] coordinators = new byte[4];
    private AtomicBoolean isUsingObjectFollow = new AtomicBoolean(false);
    private Socket socket;
    private BufferedWriter bufferedWriter;
    private BlockingQueue<String> logQ = new LinkedBlockingQueue<>();
    /*
        DJI sdk
     */
    private int currentBatteryInPercent = -1;
    private int currentSatellitesCount = -1;
    private boolean isDrawingFollowObject = true;
    private boolean isRecording = false;
    private SettingsDefinitions.CameraMode curCameraMode = SettingsDefinitions.CameraMode.UNKNOWN;
    private Camera camera;
    private DJICodecManager djiCodecManager;
    private TextureView.SurfaceTextureListener textureListener
            = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (djiCodecManager == null) {
                djiCodecManager = new DJICodecManager(MainActivity.this, surface, width, height);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (djiCodecManager != null) {
                djiCodecManager.cleanSurface();
                djiCodecManager = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };
    private Battery battery;
    private FlightController flightController;

    private List<Waypoint> wayPointList = new ArrayList<>();

    private BaiduMap.OnMapLongClickListener onMapLongClickListener
            = new BaiduMap.OnMapLongClickListener() {
        @Override
        public void onMapLongClick(LatLng latLng) {
            LatLng latLngGPS84 = CoordinationConverter.BD092GPS84(latLng);
            wayPointList.add(new Waypoint(
                    latLngGPS84.latitude,
                    latLngGPS84.longitude,
                    waypointMissionFlightHeight)
            );
            baiduMap.addOverlay(new MarkerOptions()
                    .position(latLng)
                    .animateType(MarkerOptions.MarkerAnimateType.grow)
                    .flat(true)
                    .anchor(0.5F, 0.5F)
                    .icon(BitmapDescriptorFactory.fromResource(R.mipmap.marker))
                    .draggable(false));
            final int pointListSize = wayPointList.size();
            if (pointListSize > 1) {
                baiduMap.addOverlay(new PolylineOptions()
                        .points(new ArrayList<LatLng>() {
                            {
                                add(CoordinationConverter.GPS2BD09(new LatLng(
                                        wayPointList.get(pointListSize - 1).coordinate.getLatitude(),
                                        wayPointList.get(pointListSize - 1).coordinate.getLongitude()
                                )));
                                add(CoordinationConverter.GPS2BD09(new LatLng(
                                        wayPointList.get(pointListSize - 2).coordinate.getLatitude(),
                                        wayPointList.get(pointListSize - 2).coordinate.getLongitude()
                                )));
                            }
                        })
                        .color(R.color.purple)
                        .dottedLine(true)
                );
            }
        }
    };


    private View.OnTouchListener videoTextureViewFrameLayoutOnTouchListener
            = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    rectView.setX1(event.getX());
                    rectView.setY1(event.getY());
                    rectView.setX2(event.getX());
                    rectView.setY2(event.getY());
                    rectView.setVisibility(View.VISIBLE);
                    break;
                case MotionEvent.ACTION_MOVE:
                    rectView.setX2(event.getX());
                    rectView.setY2(event.getY());
                    rectView.invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    videoTextureViewFrameLayout.setOnTouchListener(null);
                    rectView.setVisibility(View.GONE);
                    //followLinearLayout.setVisibility(View.VISIBLE);

                    coordinators[0] = screenSizeConverter.convertX2XPercent(rectView.getX1());
                    coordinators[1] = screenSizeConverter.convertY2YPercent(rectView.getY1());
                    coordinators[2] = screenSizeConverter.convertX2XPercent(rectView.getX2());
                    coordinators[3] = screenSizeConverter.convertY2YPercent(rectView.getY2());

                    if (flightController != null && baseProduct != null && baseProduct.isConnected()) {
                        byte[] data = OnboardDataEncoder.encode(OnboardDataEncoder.DataType.OBJECT_TRACKING_VALUE, coordinators);
                        sendLogToServer(String.format(Locale.CHINA, "Sent: %d %d %d %d", data[2], data[3], data[4], data[5]));
                        flightController.sendDataToOnboardSDKDevice(data, new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError djiError) {
                                if (djiError == null) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            SideToast.makeText(MainActivity.this, "Uploading target information", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                                        }
                                    });
                                } else {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            SideToast.makeText(MainActivity.this, "Failed to upload target information", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                                        }
                                    });
                                }
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Failed to upload target information: Aircraft is not connected", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                            }
                        });
                    }

                    rectView.setX1(0.0f);
                    rectView.setY1(0.0f);
                    rectView.setX2(0.0f);
                    rectView.setY2(0.0f);

                    rectView.setVisibility(View.VISIBLE);


                    break;

            }
            return true;
        }
    };
    private FlightController.OnboardSDKDeviceDataCallback onboardSDKDeviceDataCallback
            = new FlightController.OnboardSDKDeviceDataCallback() {
        @Override
        public void onReceive(final byte[] bytes) {
            sendLogToServer(String.format(Locale.CHINA, "Received:  %02x %02x %d %d %d %d %d %d", bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]));
//
            if (bytes[0] == 0x01) {
                switch (bytes[1]) {
                    case 0x02:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Visual Assist Landing Enabled", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                            }
                        });
                        break;
                    case 0x04:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Visual assisted landing has been cancelled", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                            }
                        });
                        break;
                    case 0x06:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Began to fall vertically", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                            }
                        });
                        break;
                    case 0x08:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Landed successfully", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                                final byte[] data = OnboardDataEncoder.encode(OnboardDataEncoder.DataType.VISUAL_LANDING_STOP, null);
                                flightController.sendDataToOnboardSDKDevice(data, new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            flightController.sendDataToOnboardSDKDevice(data, new CommonCallbacks.CompletionCallback() {
                                                @Override
                                                public void onResult(DJIError djiError) {
                                                    if (djiError != null) {
                                                        runOnUiThread(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                SideToast.makeText(MainActivity.this, "Visual aid termination failure", SideToast.LENGTH_SHORT, SideToast.LENGTH_SHORT);
                                                            }
                                                        });
                                                    }
                                                }
                                            });
                                        }
                                    }
                                });
                            }
                        });
                        break;
                    case 0x42:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String str;
                                if (bytes[6] == -1 && bytes[7] == -1) {
                                    str = String.format(Locale.CHINA, "L:  deltaX: -%d.%d    deltaY: -%d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                } else {
                                    if (bytes[6] == -1) {
                                        str = String.format(Locale.CHINA, "L:  deltaX: -%d.%d    deltaY: %d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                    } else {
                                        if (bytes[7] == -1) {
                                            str = String.format(Locale.CHINA, "L:  deltaX: %d.%d    deltaY: -%d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                        } else {
                                            str = String.format(Locale.CHINA, "L:  deltaX: %d.%d    deltaY: %d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                        }
                                    }
                                }

                            }
                        });
                        break;
                    case 0x44:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                //// TODO: 2017/5/12 update circle and radius
                            }
                        });
                        break;
                    default:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Unrecognizable message", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR);
                            }
                        });
                        break;
                }
                return;
            }

            if (bytes[0] == 0x02) {
                switch (bytes[1]) {
                    case 0x02:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Swipe on the screen to frame the target", SideToast.LENGTH_SHORT, SideToast.TYPE_WARNING).show();
                                isUsingObjectFollow.set(true);

                                videoTextureViewFrameLayout.setOnTouchListener(videoTextureViewFrameLayoutOnTouchListener);
                            }
                        });
                        break;
                    case 0x04:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Target tracking cancelled", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();

                                isUsingObjectFollow.set(false);

                                rectView.setVisibility(View.GONE);

                            }
                        });
                        break;
                    case 0x12:
                        break;
                    case 0x42:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String str;
                                if (bytes[6] == -1 && bytes[7] == -1) {
                                    str = String.format(Locale.CHINA, "T:  deltaX: -%d.%d    deltaY: -%d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                } else {
                                    if (bytes[6] == -1) {
                                        str = String.format(Locale.CHINA, "T:  deltaX: -%d.%d    deltaY: %d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                    } else {
                                        if (bytes[7] == -1) {
                                            str = String.format(Locale.CHINA, "T:  deltaX: %d.%d    deltaY: -%d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                        } else {
                                            str = String.format(Locale.CHINA, "T:  deltaX: %d.%d    deltaY: %d.%d", bytes[2], bytes[3], bytes[4], bytes[5]);
                                        }
                                    }
                                }

                            }
                        });
                        break;
                    case 0x44:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                rectView.setX1(screenSizeConverter.convertXPercent2X(bytes[2]));
                                rectView.setY1(screenSizeConverter.convertYPercent2Y(bytes[3]));
                                rectView.setX2(screenSizeConverter.convertXPercent2X(bytes[4]));
                                rectView.setY2(screenSizeConverter.convertYPercent2Y(bytes[5]));

                                if (isDrawingFollowObject) {
                                    rectView.invalidate();
                                }
                            }
                        });
                        break;
                    default:
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SideToast.makeText(MainActivity.this, "Unrecognizable message", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                            }
                        });
                        break;
                }
            }

        }
    };
    private BaseProduct.BaseProductListener baseProductListener
            = new BaseProduct.BaseProductListener() {
        @Override
        public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent baseComponent, BaseComponent baseComponent1) {

        }

        @Override
        public void onConnectivityChange(final boolean b) {
            if (b) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        SideToast.makeText(MainActivity.this, "The aircraft is connected", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                        //aircraftTextView.setText(baseProduct.getModel().toString());
                    }
                });
                changeCameraState();
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        SideToast.makeText(MainActivity.this, "The aircraft has been disconnected", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                        statusHorizontalVelocityTextView.setText("N/A");
                        statusHorizontalDistanceTextView.setText("N/A");
                        statusVerticaLDistanceTextView.setText("N/A");
                        statusVerticalVelocityTextView.setText("N/A");
                        currentBatteryInPercent = -1;
                        curCameraMode = SettingsDefinitions.CameraMode.UNKNOWN;
                    }
                });
            }
        }
    };
    private FlightControllerState.Callback fcsCallback
            = new FlightControllerState.Callback() {
        @Override
        public void onUpdate(@NonNull final FlightControllerState flightControllerState) {
            updateFlightParams(flightControllerState);
            updateBaiduMapMyLocation(flightControllerState);
            updateSatellitesCount(flightControllerState.getSatelliteCount());
        }
    };
    private BatteryState.Callback batteryCallback
            = new BatteryState.Callback() {
        @Override
        public void onUpdate(BatteryState batteryState) {
            int remainingBatteryInPercent = batteryState.getChargeRemainingInPercent();
            updateBatteryState(remainingBatteryInPercent);
        }
    };
    private DJISDKManager.SDKManagerCallback sdkManagerCallback
            = new DJISDKManager.SDKManagerCallback() {

        @Override
        public void onRegister(DJIError djiError) {
            if (djiError.toString().equals(DJISDKError.REGISTRATION_SUCCESS.toString())) {
                startUpInfoDialog.dismiss();
                DJISDKManager.getInstance().startConnectionToProduct();
            } else {
                SimpleAlertDialog.show(
                        MainActivity.this,
                        false,
                        "verification failed",
                        "Please check the network connection",
                        new SimpleDialogButton("drop out", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface x, int y) {
                                System.exit(0);
                            }
                        })
                );
            }
        }

        @Override
        public void onProductChange(BaseProduct previousProd, BaseProduct presentProd) {
            baseProduct = presentProd;
            if (baseProduct == null) {
                return;
            }

            currentBatteryInPercent = -1;

            if (baseProduct.isConnected()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        SideToast.makeText(MainActivity.this, "The aircraft is connected", SideToast.LENGTH_SHORT, SideToast.TYPE_NORMAL).show();
                    }
                });

                initFlightController();
                initBattery();
                initCamera();
                changeCameraState();
                baseProduct.setBaseProductListener(baseProductListener);


                List<VideoFeeder.VideoFeed> videoFeeds = VideoFeeder.getInstance().getVideoFeeds();
                if (videoFeeds.size() != 0) {
                    videoFeeds.get(0).setCallback(new VideoFeeder.VideoDataCallback() {
                        @Override
                        public void onReceive(byte[] videoBuffer, int size) {
                            if (djiCodecManager != null) {
                                djiCodecManager.sendDataToDecoder(videoBuffer, size);
                            }
                        }
                    });
                }
            }

        }
    };

    private void sendLogToServer(final String str) {
        logQ.add(str + LINE_BROKER);
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_IMMERSIVE);
        getWindow().getAttributes().systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE;

        SDKInitializer.initialize(getApplicationContext());
        setContentView(R.layout.activity_main);

        initPermissionRequest();
        initUI();
        initBaiduMap();
        initVideoTextureView();
        initOnClickListener();

        checkSocketConnection();

        startUpInfoDialog = new SimpleProgressDialog(MainActivity.this, "Verifying application…");

        startUpInfoDialog.show();
        DJISDKManager.getInstance().registerApp(this, sdkManagerCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        mapView.onResume();
    }

    private void initBaiduMap() {
        mapViewPanel = (RelativeLayout) LayoutInflater.from(this).inflate(R.layout.map_panel, null);
        linearLayoutForMap = (LinearLayout) findViewById(R.id.ll_for_map);
        mapView = (MapView) mapViewPanel.findViewById(R.id.mv_mapview);

        linearLayoutForMap.addView(mapViewPanel);

        /*
            configure Baidu MapView
         */
        baiduMap = mapView.getMap();
        baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL);
        mapView.setClickable(true);
        mapView.showZoomControls(false);
        mapView.showScaleControl(false);
        baiduMap.setMyLocationEnabled(true);

        UiSettings uiSettings = baiduMap.getUiSettings();
        uiSettings.setCompassEnabled(false);
        uiSettings.setAllGesturesEnabled(false);
        uiSettings.setScrollGesturesEnabled(true);
        uiSettings.setZoomGesturesEnabled(true);


        // zoom the map 6 times to ensure it is large enough :)
        for (int i = 0; i < 6; i++)
            baiduMap.setMapStatus(MapStatusUpdateFactory.zoomIn());

        baiduMap.setMyLocationConfigeration(new MyLocationConfiguration(
                MyLocationConfiguration.LocationMode.FOLLOWING,
                true,
                null));

    }

    private void initVideoTextureView() {
        videoTextureView = new TextureView(this);
        videoTextureView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        videoTextureView.setElevation(0);
        videoTextureView.setSurfaceTextureListener(textureListener);
        videoTextureViewFrameLayout.addView(videoTextureView);

    }

    private void initUI() {
        videoTextureViewFrameLayout = (FrameLayout) findViewById(R.id.videoTextureViewLayout);
        relativeLayoutMain = (RelativeLayout) findViewById(R.id.rl_main);
        remainingBatteryImageView = (ImageView) findViewById(R.id.remaining_battery);
        cameraShootImageView = (ImageView) findViewById(R.id.camera_take);
        cameraSwitchImageView = (ImageView) findViewById(R.id.camera_switch);

        satelliteNumberTextView = (TextView) findViewById(R.id.satellite_number_txt);
        statusVerticaLDistanceTextView = (TextView) findViewById(R.id.altitude_txt);
        statusHorizontalDistanceTextView = (TextView) findViewById(R.id.horizontal_distance_txt);
        statusHorizontalVelocityTextView = (TextView) findViewById(R.id.velocity_txt);
        statusVerticalVelocityTextView = (TextView) findViewById(R.id.vertical_velocity_txt);


        rectView = new RectView(MainActivity.this);
        rectView.setX1(0.0f);
        rectView.setY1(0.0f);
        rectView.setX2(0.0f);
        rectView.setY2(0.0f);

        rectView.setElevation(Integer.MAX_VALUE);


        videoTextureViewFrameLayout.addView(rectView);

        developSwitchGroup = new Switch[7];


        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(500, ViewGroup.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_START);



        screenSizeConverter = new ScreenSizeConverter(MainActivity.this);
    }

    private void initPermissionRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //noinspection deprecation
            this.requestPermissions(
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.VIBRATE,
                            Manifest.permission.INTERNET,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.WAKE_LOCK,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_NETWORK_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CHANGE_WIFI_STATE,
                            Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.SYSTEM_ALERT_WINDOW,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.WRITE_SETTINGS,
                            Manifest.permission.GET_TASKS,
                            Manifest.permission.CHANGE_CONFIGURATION,
                    }
                    , Integer.MAX_VALUE);
        }
    }

    private void initFlightController() {
        flightController = FlightControllerManager.getInstance(baseProduct);
        flightController.setStateCallback(fcsCallback);
        flightController.setOnboardSDKDeviceDataCallback(onboardSDKDeviceDataCallback);
    }

    private void initBattery() {
        battery = BatteryManager.getInstance(baseProduct);
        battery.setStateCallback(batteryCallback);
    }

    private void initCamera() {
        if (baseProduct != null && baseProduct.isConnected()) {
            camera = baseProduct.getCamera();
        }
    }



    private void initOnClickListener() {


        cameraShootImageView.setClickable(true);
        cameraShootImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseProduct == null || !baseProduct.isConnected()) {
                    SideToast.makeText(MainActivity.this, "Invalid operation: The plane is not connected", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR);
                } else {
                    switch (curCameraMode) {
                        case SHOOT_PHOTO:
                            camera.startShootPhoto(null);
                            break;
                        case RECORD_VIDEO:
                            if (isRecording) {
                                isRecording = false;
                                camera.stopRecordVideo(new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    SideToast.makeText(MainActivity.this, "Camera error", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                                                }
                                            });
                                        } else {
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    SideToast.makeText(MainActivity.this, "Video stopped", SideToast.LENGTH_SHORT, SideToast.TYPE_WARNING).show();
                                                }
                                            });
                                        }
                                    }
                                });
                            } else {
                                isRecording = true;
                                camera.startRecordVideo(new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError != null) {
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    SideToast.makeText(MainActivity.this, "Camera error", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                                                }
                                            });
                                        } else {
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    SideToast.makeText(MainActivity.this, "Started recording", SideToast.LENGTH_SHORT, SideToast.TYPE_WARNING).show();
                                                }
                                            });
                                        }
                                    }
                                });
                            }
                            break;
                        case UNKNOWN:
                            SideToast.makeText(MainActivity.this, "Camera connection error", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR);
                            break;
                    }
                }
            }
        });

        cameraSwitchImageView.setClickable(true);
        cameraSwitchImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseProduct == null || !baseProduct.isConnected()) {
                    SideToast.makeText(MainActivity.this, "Invalid operation: The plane is not connected", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR);
                } else {
                    switch (curCameraMode) {
                        case SHOOT_PHOTO:
                            camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError == null) {
                                        curCameraMode = SettingsDefinitions.CameraMode.RECORD_VIDEO;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                cameraShootImageView.setImageDrawable(MainActivity.this.getDrawable(R.mipmap.camera_record));
                                            }
                                        });
                                    } else {
                                        SideToast.makeText(MainActivity.this, "Failed to switch camera status", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                                    }
                                }
                            });
                            break;
                        case RECORD_VIDEO:
                            camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError == null) {
                                        curCameraMode = SettingsDefinitions.CameraMode.SHOOT_PHOTO;
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                cameraShootImageView.setImageDrawable(MainActivity.this.getDrawable(R.mipmap.camera_take));
                                            }
                                        });
                                    } else {
                                        SideToast.makeText(MainActivity.this, "Failed to switch camera status", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR).show();
                                    }
                                }
                            });
                            break;
                        case UNKNOWN:
                            SideToast.makeText(MainActivity.this, "Camera connection error", SideToast.LENGTH_SHORT, SideToast.TYPE_ERROR);
                            break;
                    }
                }
            }
        });


    }

    private void clearWaypoint() {
        baiduMap.clear();
        wayPointList.clear();
        System.gc();
    }

    private void checkSocketConnection() {
        new Thread() {
            @Override
            public void run() {
                String buff = null;
                while (true) {
                    if (socket == null || !socket.isConnected()) {
                        try {
                            socket = new Socket("192.168.1.101", 3000);
                            bufferedWriter = new BufferedWriter((new OutputStreamWriter(socket.getOutputStream())));
                            while (true) {
                                if (!socket.isConnected()) throw new Exception();
                                if (buff != null) {
                                    bufferedWriter.write(buff);
                                    bufferedWriter.flush();
                                }
                                buff = logQ.poll(3, TimeUnit.SECONDS);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }.start();
    }

    private void updateBatteryState(int remainingBattery) {
        if (currentBatteryInPercent == -1) {
            final int temp[] = new int[1];
            switch ((remainingBattery + 10) / 20) {
                case 0:
                    temp[0] = R.mipmap.battery_0;
                    currentBatteryInPercent = 0;
                    break;
                case 1:
                    currentBatteryInPercent = 1;
                    temp[0] = R.mipmap.battery_10;
                    break;
                case 2:
                    currentBatteryInPercent = 2;
                    temp[0] = R.mipmap.battery_30;
                    break;
                case 3:
                    currentBatteryInPercent = 3;
                    temp[0] = R.mipmap.battery_50;
                    break;
                case 4:
                    currentBatteryInPercent = 4;
                    temp[0] = R.mipmap.battery_70;
                    break;
                case 5:
                    currentBatteryInPercent = 5;
                    temp[0] = R.mipmap.battery_100;
                    break;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    remainingBatteryImageView.setImageDrawable(MainActivity.this.getDrawable(temp[0]));
                }
            });
        } else {
            int flag = (remainingBattery + 10) / 20;
            if (flag != currentBatteryInPercent) {
                final int temp[] = new int[1];
                switch (flag) {
                    case 0:
                        temp[0] = R.mipmap.battery_0;
                        currentBatteryInPercent = 0;
                        break;
                    case 1:
                        currentBatteryInPercent = 1;
                        temp[0] = R.mipmap.battery_10;
                        break;
                    case 2:
                        currentBatteryInPercent = 2;
                        temp[0] = R.mipmap.battery_30;
                        break;
                    case 3:
                        currentBatteryInPercent = 3;
                        temp[0] = R.mipmap.battery_50;
                        break;
                    case 4:
                        currentBatteryInPercent = 4;
                        temp[0] = R.mipmap.battery_70;
                        break;
                    case 5:
                        currentBatteryInPercent = 5;
                        temp[0] = R.mipmap.battery_100;
                        break;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        remainingBatteryImageView.setImageDrawable(MainActivity.this.getDrawable(temp[0]));
                    }
                });
            }


        }
    }

    private void updateSatellitesCount(final int satellitesCount) {
        if (satellitesCount != currentSatellitesCount) {
            currentSatellitesCount = satellitesCount;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    satelliteNumberTextView.setText(String.valueOf(satellitesCount));
                }
            });
        }
    }

    private void updateFlightParams(final FlightControllerState flightControllerState) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusVerticaLDistanceTextView.setText(String.format(Locale.CHINA, "%.1f", flightControllerState.getAircraftLocation().getAltitude()));
                float velocity = (float) Math.sqrt(Math.pow(flightControllerState.getVelocityX(), 2) + Math.pow(flightControllerState.getVelocityY(), 2));
                statusHorizontalVelocityTextView.setText(String.format(Locale.CHINA, "%.1f", velocity));
                statusVerticalVelocityTextView.setText(String.format(Locale.CHINA, "%.1f", (int) (flightControllerState.getVelocityZ() * 10) == 0 ? 0.0000f : (-1.0) * flightControllerState.getVelocityZ()));
                double distance = LLDistanceConverter.LL2Distance(flightControllerState.getHomeLocation().getLatitude(), flightControllerState.getHomeLocation().getLongitude(), flightControllerState.getAircraftLocation().getLatitude(), flightControllerState.getAircraftLocation().getLongitude());
                statusHorizontalDistanceTextView.setText(String.format(Locale.CHINA, "%.1f", distance));
            }
        });
    }

    private void updateBaiduMapMyLocation(FlightControllerState flightControllerState) {
        LatLng cvLatLong = CoordinationConverter.GPS2BD09(
                new LatLng(
                        flightControllerState.getAircraftLocation().getLatitude(),
                        flightControllerState.getAircraftLocation().getLongitude()
                )
        );
        MyLocationData locationData = new MyLocationData.Builder()
                .latitude(cvLatLong.latitude)
                .longitude(cvLatLong.longitude)
                .direction(flightControllerState.getAircraftHeadDirection())
                .build();
        baiduMap.setMyLocationData(locationData);
    }

    private void changeCameraState() {
        if (camera != null) {
            camera.getMode(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.CameraMode>() {
                @Override
                public void onSuccess(SettingsDefinitions.CameraMode cameraMode) {
                    if (cameraMode.equals(SettingsDefinitions.CameraMode.SHOOT_PHOTO)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                cameraShootImageView.setImageDrawable(MainActivity.this.getDrawable(R.mipmap.camera_take));
                                curCameraMode = SettingsDefinitions.CameraMode.SHOOT_PHOTO;
                            }
                        });
                    } else {
                        if (cameraMode.equals(SettingsDefinitions.CameraMode.RECORD_VIDEO)) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    cameraShootImageView.setImageDrawable(MainActivity.this.getDrawable(R.mipmap.camera_record));
                                    curCameraMode = SettingsDefinitions.CameraMode.RECORD_VIDEO;
                                }
                            });
                        }
                    }
                }

                @Override
                public void onFailure(DJIError djiError) {
                    Log.e("Camera State error", ">>" + djiError.toString());
                    curCameraMode = SettingsDefinitions.CameraMode.UNKNOWN;
                }
            });
        }
    }


}
