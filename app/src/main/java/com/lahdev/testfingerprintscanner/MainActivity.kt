package com.lahdev.testfingerprintscanner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

import com.suprema.BioMiniFactory;
import com.suprema.CaptureResponder
import com.suprema.IBioMiniDevice
import com.suprema.IBioMiniDevice.*
import com.suprema.IUsbEventHandler
import com.suprema.IUsbEventHandler.DeviceChangeEvent

class MainActivity : AppCompatActivity() {
    ///CONSTANTS
    private val ACTION_USB_PERMISSION: String? = "com.android.example.USB_PERMISSION"
    private var mPermissionIntent: PendingIntent? = null

    ///VARIABLES
    private lateinit var mainContext: MainActivity//? = null

    private var mUsbDevice: UsbDevice? = null

    private var mBioMiniFactory: BioMiniFactory? = null
    private var mbUsbExternalUSBManager = false
    private lateinit var mUsbManager: UsbManager
    private var mCurrentDevice: IBioMiniDevice? = null

    private val mCaptureOptionDefault = CaptureOption()
    private lateinit var mWakeLock: WakeLock

    private val mDetect_core = false
    private val mTemplateQualityEx = false

    //////Variables with overrides and callbacks
    private val mCaptureResponseDefault: CaptureResponder = object : CaptureResponder() {
        override fun onCaptureEx(
            context: Any, option: CaptureOption, capturedImage: Bitmap,
            capturedTemplate: TemplateData?,
            fingerState: FingerState
        ): Boolean {
            if (capturedTemplate != null) {
                if (capturedTemplate.data.size > 0) {
                    val nFeature = mCurrentDevice!!.getFeatureNumber(
                        capturedTemplate.data,
                        capturedTemplate.data.size
                    )
                    Log.i(TAG, "getFeatureNumber() = $nFeature")
                }
            }
            if (mCurrentDevice!!.lfdLevel > 0) {
                Log.i(TAG, "LFD SCORE : " + mCurrentDevice!!.lfdResult)
            }
            if (mDetect_core == true && capturedTemplate != null) {
                val _coord = capturedTemplate.coreCoordinate
                Log.i(TAG, "Core Coordinate X : " + _coord[0] + " Y : " + _coord[1])
            }
            if (mTemplateQualityEx == true && capturedTemplate != null) {
                val _templateQualityExValue = mCurrentDevice!!.templateQualityExValue
                Log.i(TAG, "template Quality : $_templateQualityExValue")
            }
            //printState(resources.getText(R.string.capture_single_ok))
            //            log(((IBioMiniDevice) context).popPerformanceLog());
            runOnUiThread {
                if (capturedImage != null) {
                    val iv = findViewById<ImageView>(R.id.imagePreview)
                    if(iv!=null) {
                        iv.setImageBitmap(capturedImage)
                        Log.i(TAG, "=====StartCapturing catpuredImage")

                    }
                }
            }
            return true
        }

        override fun onCaptureError(contest: Any, errorCode: Int, error: String) {
            //Log.i(TAG,"onCaptureError : " + error + " ErrorCode :" + errorCode);
            if (errorCode == ErrorCode.CTRL_ERR_IS_CAPTURING.value()) {
                Log.i(TAG, "Other capture function is running. abort capture function first!")
            } else if (errorCode == ErrorCode.CTRL_ERR_CAPTURE_ABORTED.value()) {
//              Log.i(TAG,"Capture aborted!");
                //see abort capture result.
            } else if (errorCode == ErrorCode.CTRL_ERR_FAKE_FINGER.value()) {
                Log.i(TAG, "Fake Finger Detected")
            } else Log.i(TAG, "Capture did not performed by $error")
        }
    }
    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (mUsbDevice != null) {
                            if (mBioMiniFactory == null) return
                            Log.i(TAG, "USB_PERMISSION_GRANTED. add device to BiominiFactory")
                            val result: Boolean =
                                mBioMiniFactory?.addDevice(mUsbDevice) == true
                            if (result == false) {
                                Log.i(TAG, "add device is faile")
                            } else {
                            }
                        } else {
                        }
                    } else {
                        Log.d(TAG, "permission denied for device$mUsbDevice")
                    }
                }
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                Log.i(TAG, "ACTION_USB_DEVICE_ATTACHED")
                addDeviceToUsbDeviceList()
                if (mUsbManager.hasPermission(mUsbDevice) == false && mBioMiniFactory != null) {
                    Log.i(TAG, "requestPermission!")
                    mPermissionIntent = PendingIntent.getBroadcast(
                        mainContext,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        0
                    )
                    mUsbManager.requestPermission(mUsbDevice, mPermissionIntent)
                }
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                Log.i(TAG, "ACTION_USB_DEVICE_DETACHED")
                mBioMiniFactory?.removeDevice(mUsbDevice)
                mUsbDevice = null
            }
        }
    }

    ////////////////////ACTIVITY METHODS///////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestWakeLock()
        mainContext = this
        mCaptureOptionDefault.frameRate = FrameRate.SHIGH

        setupViewElements()

        mBioMiniFactory?.close()

        if (mbUsbExternalUSBManager) {
            checkDevice()
        }

        mUsbManager = getSystemService(USB_SERVICE) as UsbManager
        initUsbListener()
        addDeviceToUsbDeviceList()
        restartBioMini()
    }

    fun setupViewElements() {
        findViewById<Button>(R.id.startBtn).setOnClickListener { v ->
            autoScan()
        }

    }

    ///////////////SCANNER CONFIG//////////////////////
    fun autoScan() {
        mCaptureOptionDefault.captureFuntion = IBioMiniDevice.CaptureFuntion.CATURE_AUTO
        mCaptureOptionDefault.extractParam.captureTemplate = false
        findViewById<ImageView>(R.id.imagePreview).setImageBitmap(null)

        mCurrentDevice?.let{
            val result = it.captureAuto(mCaptureOptionDefault, mCaptureResponseDefault)
            if (result == IBioMiniDevice.ErrorCode.ERR_NOT_SUPPORTED.value()) {
                Log.i(TAG, "This device is not support auto capture.")
            }
        }

    }

    fun restartBioMini() {
        mBioMiniFactory?.close()

        if (mbUsbExternalUSBManager) {
            //mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
            mBioMiniFactory = object : BioMiniFactory(mainContext, mUsbManager) {
                override fun onDeviceChange(event: DeviceChangeEvent, dev: Any) {
                    Log.i(TAG, "----------------------------------------")
                    Log.i(TAG, "onDeviceChange : $event using external usb-manager")
                    Log.i(TAG, "----------------------------------------")
                    handleDevChange(event, dev)
                }
            }
        } else {
            mBioMiniFactory = object : BioMiniFactory(mainContext) {
                override fun onDeviceChange(event: DeviceChangeEvent, dev: Any) {
                    Log.i(TAG, "----------------------------------------")
                    Log.i(TAG, "onDeviceChange : $event")
                    Log.i(TAG, "----------------------------------------")
                    handleDevChange(event, dev)
                }
            }
        }
        //mBioMiniFactory.setTransferMode(IBioMiniDevice.TransferMode.MODE2);
    }

    fun handleDevChange(event: DeviceChangeEvent, dev: Any?) {
        if (event == DeviceChangeEvent.DEVICE_ATTACHED && mCurrentDevice == null) {
            Thread {
                var cnt = 0
                while (mBioMiniFactory == null && cnt < 20) {
                    SystemClock.sleep(1000)
                    cnt++
                }
                mBioMiniFactory?.let { biominiFactory ->
                    mCurrentDevice = biominiFactory.getDevice(0)
                    //Log.i(TAG,(resources.getText(R.string.device_attached))
                    Log.d(
                        TAG,
                        "mCurrentDevice attached : $mCurrentDevice"
                    )
                    //mPager.setCurrentItem(0)
                    runOnUiThread {
                        // initSeekBarValue()
                        (findViewById(R.id.textStatus) as TextView).hint = ""
                        //mLogView.setText("")
                    }
                    mCurrentDevice?.let { iBiominiDevice ->
                        Log.i(TAG, " DeviceName : " + iBiominiDevice.getDeviceInfo().deviceName)
                        Log.i(TAG, "         SN : " + iBiominiDevice.getDeviceInfo().deviceSN)
                        Log.i(TAG, "SDK version : " + iBiominiDevice.getDeviceInfo().versionSDK)
                        if (iBiominiDevice.getDeviceInfo().scannerType.getDeviceClass() == IBioMiniDevice.ScannerClass.HID_DEVICE)
                            Log.i(TAG, "FW  version : " + iBiominiDevice.getDeviceInfo().versionFW)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            //requestWritePermission()//TODO: REVISAR SI ES NECESARIO
                        }
                    }
                }
            }.start()
        } else if (mCurrentDevice != null && event == DeviceChangeEvent.DEVICE_DETACHED && mCurrentDevice?.isEqual(
                dev
            ) == true
        ) {
            //Log.i(TAG,(resources.getText(R.string.device_detached))
            Log.d(TAG, "mCurrentDevice removed : $mCurrentDevice")
            if (mCurrentDevice != null) mCurrentDevice = null
            //this.recreate();
        }
    }

    private fun requestWakeLock() {
        Log.i(TAG, "START!")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ":BioMini WakeLock")
        mWakeLock.acquire()
    }

    fun checkDevice() {
        if (mUsbManager == null) return
        //log("checkDevice");
        Log.d(TAG, "checkDevice: ")
        val deviceList = mUsbManager.deviceList
        val deviceIter: Iterator<UsbDevice> = deviceList.values.iterator()
        while (deviceIter.hasNext()) {
            val _device = deviceIter.next()
            if (_device.vendorId == 0x16d1) {
                mUsbDevice = _device
            } else {
            }
        }
    }

    private fun initUsbListener() {
        Log.i(TAG, "start!")
        val pi = PendingIntent.getBroadcast(
            mainContext,
            0,
            Intent(ACTION_USB_PERMISSION),
            0
        )
        mainContext.registerReceiver(mUsbReceiver, IntentFilter(ACTION_USB_PERMISSION))
        val attachfilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        mainContext.registerReceiver(mUsbReceiver, attachfilter)
        val detachfilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        mainContext.registerReceiver(mUsbReceiver, detachfilter)
    }

    fun addDeviceToUsbDeviceList() {
        Log.i(TAG, "start!")
        var needUsbPermission: Boolean = false
        needUsbPermission = if (mUsbDevice != null) {
            Log.i(TAG, "usbdevice is not null!")
            return
        } else {
            true
        }
        val deviceList = mUsbManager.deviceList
        val deviceIter: Iterator<UsbDevice> = deviceList.values.iterator()
        while (deviceIter.hasNext()) {
            val _device = deviceIter.next()
            if (_device.vendorId == 0x16d1) {
                mUsbDevice = _device
                if (needUsbPermission) {
                    Log.i(TAG, "This device need to Usb Permission!")
                    mPermissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        0
                    )
                    mUsbManager.requestPermission(_device, mPermissionIntent)
                }
            } else {
            }
        }
    }
}
