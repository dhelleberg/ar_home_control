/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.dhelleberg.tango.homeardemo;

import android.os.Bundle;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.atap.tango.ux.TangoUx;
import com.google.atap.tango.ux.TangoUxLayout;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.projecttango.rajawali.DeviceExtrinsics;
import com.projecttango.rajawali.ScenePoseCalculator;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;
import com.projecttango.tangosupport.TangoSupport.IntersectionPointPlaneModelPair;

import org.rajawali3d.primitives.Plane;
import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.surface.RajawaliSurfaceView;

import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;


public class SetupActivity extends AppCompatActivity implements View.OnTouchListener, AdapterView.OnItemSelectedListener {
    private static final String TAG = SetupActivity.class.getSimpleName();
    private static final int INVALID_TEXTURE_ID = 0;
    public static final double MOVE_STEP = 0.05;
    private static final double SIZE_STEP = 0.05;

    private ARRenderer renderer;
    private TangoCameraIntrinsics mIntrinsics;
    private DeviceExtrinsics mExtrinsics;
    private TangoPointCloudManager pointCloudManager;
    private Tango mTango;
    private boolean mIsConnected = false;
    private double mCameraPoseTimestamp = 0;

    @BindView(R.id.gl_main_surface_view)
    RajawaliSurfaceView mainSurfaceView;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.tango_ux_layout)
    TangoUxLayout uxLayout;
    @BindView(R.id.bottom_sheet)
    View bottomSheet;
    @BindView(R.id.editButtons)
    View editButtons;
    @BindView(R.id.spinner)
    Spinner spinner;
    @BindView(R.id.radio_light)
    RadioButton radio_light;
    @BindView(R.id.radio_shutter)
    RadioButton radio_shutter;



    // Texture rendering related fields
    // NOTE: Naming indicates which thread is in charge of updating this variable
    private int mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean mIsFrameAvailableTangoThread = new AtomicBoolean(false);
    private double mRgbTimestampGlThread;

    public static final TangoCoordinateFramePair FRAME_PAIR = new TangoCoordinateFramePair(
            TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
            TangoPoseData.COORDINATE_FRAME_DEVICE);
    private TangoUx tangoUx;
    private boolean addMode = false;
    private Gson gson;
    private List<Item> items = new ArrayList<>();
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private Item currentItem = new Item();
    private boolean editMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        GsonBuilder gsonBuilder = new GsonBuilder();
        gson = gsonBuilder.create();
        readItems();

        tangoUx = new TangoUx(this);
        renderer = new ARRenderer(this, items);
        mainSurfaceView.setSurfaceRenderer(renderer);
        mainSurfaceView.setOnTouchListener(this);
        mainSurfaceView.setZOrderOnTop(false);
        pointCloudManager = new TangoPointCloudManager();
        tangoUx.setLayout(uxLayout);

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.openhab_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

    }

    @Override
    protected void onPause() {
        super.onPause();
        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        synchronized (this) {
            if (mIsConnected) {
                renderer.getCurrentScene().clearFrameCallbacks();
                mTango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                // We need to invalidate the connected texture ID so that we cause a re-connection
                // in the OpenGL thread after resume
                mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
                mTango.disconnect();
                tangoUx.stop();
                mIsConnected = false;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        if (!mIsConnected) {
            // Initialize Tango Service as a normal Android Service, since we call 
            // mTango.disconnect() in onPause, this will unbind Tango Service, so
            // everytime when onResume get called, we should create a new Tango object.
            mTango = new Tango(SetupActivity.this, new Runnable() {
                // Pass in a Runnable to be called from UI thread when Tango is ready,
                // this Runnable will be running on a new thread.
                // When Tango is ready, we can call Tango functions safely here only
                // when there is no UI thread changes involved.
                @Override
                public void run() {
                    try {
                        connectTango();
                        connectRenderer();
                        mIsConnected = true;
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                    }
                }
            });
        }
    }

    /**
     * Configures the Tango service and connect it to callbacks.
     */
    private void connectTango() {

        TangoUx.StartParams params = new TangoUx.StartParams();
        params.showConnectionScreen = false;
        tangoUx.start(params);

        // Use default configuration for Tango Service, plus low latency
        // IMU integration.
        TangoConfig config = mTango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // virtual objects with the RBG image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        mTango.connect(config);

        // No need to add any coordinate frame pairs since we are not
        // using pose data. So just initialize.
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
                if (tangoUx != null) {
                    tangoUx.updatePoseStatus(pose.statusCode);
                }
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // Check if the frame available is for the camera we want and update its frame
                // on the view.
                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    // Mark a camera frame is available for rendering in the OpenGL thread
                    mIsFrameAvailableTangoThread.set(true);
                    mainSurfaceView.requestRender();
                }

            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // Save the cloud and point data for later use.
                pointCloudManager.updateXyzIj(xyzIj);
                if (tangoUx != null) {
                    tangoUx.updateXyzCount(xyzIj.xyzCount);
                }
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
                if (tangoUx != null) {
                    if (event.eventKey.equals(TangoEvent.DESCRIPTION_FISHEYE_OVER_EXPOSED)) {
                        // handle the Fisheye camera issue
                    }
                    else
                        tangoUx.updateTangoEvent(event);
                }

            }
        });

        // Get extrinsics from device for use in transforms. This needs
        // to be done after connecting Tango and listeners.
        mExtrinsics = setupExtrinsics(mTango);
        mIntrinsics = mTango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
    }

    /**
     * Connects the view and renderer to the color camara and callbacks.
     */
    private void connectRenderer() {
        // Register a Rajawali Scene Frame Callback to update the scene camera pose whenever a new
        // RGB frame is rendered.
        // (@see https://github.com/Rajawali/Rajawali/wiki/Scene-Frame-Callbacks)
        renderer.getCurrentScene().registerFrameCallback(new ASceneFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {
                // NOTE: This is called from the OpenGL render thread, after all the renderer
                // onRender callbacks had a chance to run and before scene objects are rendered
                // into the scene.

                synchronized (SetupActivity.this) {
                    // Don't execute any tango API actions if we're not connected to the service
                    if (!mIsConnected) {
                        return;
                    }

                    // Set-up scene camera projection to match RGB camera intrinsics
                    if (!renderer.isSceneCameraConfigured()) {
                        renderer.setProjectionMatrix(mIntrinsics);
                    }

                    // Connect the camera texture to the OpenGL Texture if necessary
                    // NOTE: When the OpenGL context is recycled, Rajawali may re-generate the
                    // texture with a different ID.
                    if (mConnectedTextureIdGlThread != renderer.getTextureId()) {
                        mTango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                renderer.getTextureId());
                        mConnectedTextureIdGlThread = renderer.getTextureId();
                        Log.d(TAG, "connected to texture id: " + renderer.getTextureId());
                    }

                    // If there is a new RGB camera frame available, update the texture with it
                    if (mIsFrameAvailableTangoThread.compareAndSet(true, false)) {
                        mRgbTimestampGlThread =
                                mTango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                    }

                    if (mRgbTimestampGlThread > mCameraPoseTimestamp) {
                        // Calculate the device pose at the camera frame update time.
                        TangoPoseData lastFramePose = mTango.getPoseAtTime(mRgbTimestampGlThread,
                                FRAME_PAIR);
                        if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                            // Update the camera pose from the renderer
                            renderer.updateRenderCameraPose(lastFramePose, mExtrinsics);
                            mCameraPoseTimestamp = lastFramePose.timestamp;
                        } else {
                            Log.w(TAG, "Can't get device pose at time: " + mRgbTimestampGlThread);
                        }
                    }
                }
            }

            @Override
            public void onPreDraw(long sceneTime, double deltaTime) {

            }

            @Override
            public void onPostFrame(long sceneTime, double deltaTime) {

            }

            @Override
            public boolean callPreFrame() {
                return true;
            }
        });
    }

    /**
     * Calculates and stores the fixed transformations between the device and
     * the various sensors to be used later for transformations between frames.
     */
    private static DeviceExtrinsics setupExtrinsics(Tango tango) {
        // Create camera to IMU transform.
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        TangoPoseData imuTrgbPose = tango.getPoseAtTime(0.0, framePair);

        // Create device to IMU transform.
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_DEVICE;
        TangoPoseData imuTdevicePose = tango.getPoseAtTime(0.0, framePair);

        // Create depth camera to IMU transform.
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH;
        TangoPoseData imuTdepthPose = tango.getPoseAtTime(0.0, framePair);

        return new DeviceExtrinsics(imuTdevicePose, imuTrgbPose, imuTdepthPose);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if(addMode) {
            if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                // Calculate click location in u,v (0;1) coordinates.
                float u = motionEvent.getX() / view.getWidth();
                float v = motionEvent.getY() / view.getHeight();

                try {
                    // Fit a plane on the clicked point using the latest poiont cloud data
                    // Synchronize against concurrent access to the RGB timestamp in the OpenGL thread
                    // and a possible service disconnection due to an onPause event.
                    TangoPoseData planeFitPose;
                    synchronized (this) {
                        planeFitPose = doFitPlane(u, v, mRgbTimestampGlThread);
                    }

                    if (planeFitPose != null) {
                        // Update the position of the rendered cube to the pose of the detected plane
                        // This update is made thread safe by the renderer
                        renderer.updateObjectPose(planeFitPose);
                    }

                } catch (TangoException t) {
                    Toast.makeText(getApplicationContext(),
                            R.string.failed_measurement,
                            Toast.LENGTH_SHORT).show();
                    Log.e(TAG, getString(R.string.failed_measurement), t);
                } catch (SecurityException t) {
                    Toast.makeText(getApplicationContext(),
                            R.string.failed_permissions,
                            Toast.LENGTH_SHORT).show();
                    Log.e(TAG, getString(R.string.failed_permissions), t);
                }
            }
        }
        return true;
    }

    /**
     * Use the TangoSupport library with point cloud data to calculate the plane
     * of the world feature pointed at the location the camera is looking.
     * It returns the pose of the fitted plane in a TangoPoseData structure.
     */
    private TangoPoseData doFitPlane(float u, float v, double rgbTimestamp) {
        TangoXyzIjData xyzIj = pointCloudManager.getLatestXyzIj();

        if (xyzIj == null) {
            return null;
        }

        // We need to calculate the transform between the color camera at the
        // time the user clicked and the depth camera at the time the depth
        // cloud was acquired.
        TangoPoseData colorTdepthPose = TangoSupport.calculateRelativePose(
                rgbTimestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                xyzIj.timestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH);

        // Perform plane fitting with the latest available point cloud data.
        IntersectionPointPlaneModelPair intersectionPointPlaneModelPair =
                TangoSupport.fitPlaneModelNearClick(xyzIj, mIntrinsics,
                        colorTdepthPose, u, v);

        // Get the device pose at the time the plane data was acquired.
        TangoPoseData devicePose =
                mTango.getPoseAtTime(xyzIj.timestamp, FRAME_PAIR);

        // Update the AR object location.
        TangoPoseData planeFitPose = ScenePoseCalculator.planeFitToTangoWorldPose(
                intersectionPointPlaneModelPair.intersectionPoint,
                intersectionPointPlaneModelPair.planeModel, devicePose, mExtrinsics);

        return planeFitPose;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.setup_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected");
        switch (item.getItemId()) {
            case R.id.menu_add:
                startAddMode();
                return true;
            case R.id.menu_ok:
                addItem();
                return true;
            case R.id.menu_edit_mode:
                toggleEditMode();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private void toggleEditMode() {
        editMode = !editMode;
        if(editMode)
            editButtons.setVisibility(View.VISIBLE);
        else
            editButtons.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void addItem() {
        addMode = false;
        invalidateOptionsMenu();
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        Log.d(TAG, "saving item");
        //saving current item
        currentItem = new Item();
        Plane currentObject = renderer.getCurrentEditObject();
        currentItem.scale_x = currentObject.getScaleX();
        currentItem.scale_y = currentObject.getScaleY();
        currentItem.pos_x = currentObject.getX();
        currentItem.pos_y = currentObject.getY();
        currentItem.pos_z = currentObject.getZ();

        currentItem.quat_w = currentObject.getOrientation().w;
        currentItem.quat_x = currentObject.getOrientation().x;
        currentItem.quat_y = currentObject.getOrientation().y;
        currentItem.quat_z = currentObject.getOrientation().z;


    }

    private void startAddMode() {
        addMode = true;
        invalidateOptionsMenu();
    }

    private void readItems() {
        Reader reader = null;
        try {
            reader = new InputStreamReader(openFileInput("data.json"));
            items = gson.fromJson(reader, new TypeToken<List<Item>>(){}.getType());

        } catch (FileNotFoundException e) {

            Log.e(TAG, "could not read object", e);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if(editMode) {
            MenuItem item = menu.findItem(R.id.menu_add);
            item.setVisible(!addMode);

            item = menu.findItem(R.id.menu_ok);
            item.setVisible(addMode);
        }
        else {
            MenuItem item = menu.findItem(R.id.menu_add);
            item.setVisible(false);

            item = menu.findItem(R.id.menu_ok);
            item.setVisible(false);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @OnClick({R.id.ib_up, R.id.ib_down, R.id.ib_left, R.id.ib_right,
            R.id.ib_height_minus, R.id.ib_height_minus_plus,
            R.id.ib_width_minus, R.id.ib_width_plus})
    public void handleAdjustClicks(View view) {
        Log.d(TAG, "handle click: "+view.toString());

        switch (view.getId()) {
            case R.id.ib_up:
                renderer.adjustPosition(-MOVE_STEP, 0);
                break;
            case R.id.ib_down:
                renderer.adjustPosition(MOVE_STEP, 0);
                break;
            case R.id.ib_left:
                renderer.adjustPosition(0, MOVE_STEP);
                break;
            case R.id.ib_right:
                renderer.adjustPosition(0, -MOVE_STEP);
                break;

            case R.id.ib_height_minus:
                renderer.adjustSize(0, -SIZE_STEP);
                break;
            case R.id.ib_height_minus_plus:
                renderer.adjustSize(0, SIZE_STEP);
                break;
            case R.id.ib_width_minus:
                renderer.adjustSize(-SIZE_STEP, 0);
                break;
            case R.id.ib_width_plus:
                renderer.adjustSize(SIZE_STEP, 0);
                break;
        }
    }

    @OnClick(R.id.button_OK)
    public void okFromBottomSheet() {
        items.add(currentItem);
        if(radio_light.isChecked())
            currentItem.type = Item.TYPE.TYPE_LIGHT;
        else if(radio_shutter.isChecked())
            currentItem.type = Item.TYPE.TYPE_SHUTTER;

        try {
            Writer writer = new OutputStreamWriter(openFileOutput("data.json",0));
            gson.toJson(items, writer);
            writer.flush();
            Log.d(TAG, "saving:"+gson.toJson(currentItem));
            Toast.makeText(this, "saved object", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "could not write object", e);
        }
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        Log.d(TAG, "Spinner selected pos: "+parent.getItemAtPosition(position));
        currentItem.openHabID = (String) parent.getItemAtPosition(position);

    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }
}