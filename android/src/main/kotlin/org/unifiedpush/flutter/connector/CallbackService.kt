package org.unifiedpush.flutter.connector

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import androidx.core.app.JobIntentService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.FlutterCallbackInformation
import io.flutter.view.FlutterMain
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class CallbackService : MethodCallHandler, JobIntentService() {
    private val messageQueue = ArrayDeque<String>()
    private val endpointQueue = ArrayDeque<String>()
    private val unregisteredQueue = ArrayDeque<String>()

    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

    companion object {
        @JvmStatic
        private val TAG = "FlutterUnifiedPushService"
        @JvmStatic
        private val JOB_ID = UUID.randomUUID().mostSignificantBits.toInt()
        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        val sServiceStarted = AtomicBoolean(false)

        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, CallbackService::class.java, JOB_ID, work)
        }
    }

    private fun startService(context: Context) {
        synchronized(sServiceStarted) {
            mContext = context
            if (sBackgroundFlutterEngine == null) {
                val callbackHandle = context.getSharedPreferences(
                        SHARED_PREFERENCES_KEY,
                        Context.MODE_PRIVATE)
                        .getLong(CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                if (callbackHandle == 0L) {
                    Log.e(TAG, "Fatal: no callback registered")
                    return
                }
                Log.d(TAG, callbackHandle.toString())
                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

                Log.i(TAG, "Starting FlutterUnifiedPushService...")
                sBackgroundFlutterEngine = FlutterEngine(context)

                val args = DartCallback(
                        context.assets,
                        FlutterMain.findAppBundlePath(context)!!,
                        callbackInfo
                )
                sBackgroundFlutterEngine!!.dartExecutor.executeDartCallback(args)
            }
        }
        mBackgroundChannel = MethodChannel(
                sBackgroundFlutterEngine!!.dartExecutor.binaryMessenger,
                CALLBACK_CHANNEL
        )
        mBackgroundChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when(call.method) {
            CALLBACK_EVENT_INITIALIZED -> {
                Log.i("CallbackService","EVENT_INITIALIZED")
                synchronized(sServiceStarted) {
                    unregisteredQueue.poll()?.let{
                        Log.d("CallbackService","unregisteredQueue not empty")
                        mBackgroundChannel.invokeMethod(CALLBACK_EVENT_UNREGISTERED, null)
                        unregisteredQueue.clear()
                        endpointQueue.clear()
                        messageQueue.clear()
                    }
                    endpointQueue.removeAll {
                        Log.d("CallbackService","endpointQueue not empty")
                        mBackgroundChannel.invokeMethod(CALLBACK_EVENT_NEW_ENDPOINT, it)
                        true
                    }
                    messageQueue.removeAll {
                        Log.d("CallbackService","messageQueue not empty")
                        mBackgroundChannel.invokeMethod(CALLBACK_EVENT_MESSAGE, it)
                        true
                    }
                    sServiceStarted.set(true)
                }
            }
            else -> {
                result.notImplemented()
                return
            }
        }
        result.success(null)
    }

    override fun onCreate() {
        super.onCreate()
        startService(this)
    }

    override fun onHandleWork(intent: Intent) {

        val event = intent.getStringExtra(EXTRA_CALLBACK_EVENT)?: ""
        val data = intent.getStringExtra(EXTRA_CALLBACK_DATA)?: ""

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                Log.d("CallbackService","Not yet started")
                when(event){
                    CALLBACK_EVENT_MESSAGE -> {
                        messageQueue.add(data)
                    }
                    CALLBACK_EVENT_NEW_ENDPOINT -> {
                        endpointQueue.clear()
                        endpointQueue.add(data)
                    }
                    CALLBACK_EVENT_UNREGISTERED -> {
                        unregisteredQueue.clear()
                        unregisteredQueue.add(null)
                    }
                    else -> {}
                }
            } else {
                Handler(mContext.mainLooper).post {
                    mBackgroundChannel.invokeMethod(event, data)
                }

            }
        }
    }
}
