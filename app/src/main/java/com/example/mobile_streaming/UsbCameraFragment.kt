package com.example.mobile_streaming

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class UsbCameraFragment : CameraFragment(), ICameraStateCallBack, IPreviewDataCallBack {
    companion object {
        private const val REQUEST_RETRY_DELAY_MS = 350L
        private const val REQUEST_RETRY_COUNT = 16
    }

    interface Host {
        fun onUsbCameraLog(message: String)
        fun onUsbCameraOpened()
        fun onUsbPreviewReady(width: Int, height: Int, format: IPreviewDataCallBack.DataFormat)
        fun onUsbPreviewFrame(
            data: ByteArray,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        )

        fun onUsbCameraClosed(reason: String)
        fun onUsbCameraError(message: String)
    }

    private var host: Host? = null
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: AspectRatioTextureView
    private var previewReadySent = false
    private var previewCallbackRegistered = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = when {
            parentFragment is Host -> parentFragment as Host
            activity is Host -> activity as Host
            context is Host -> context
            else -> null
        }
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        previewContainer = FrameLayout(requireContext())
        previewView = AspectRatioTextureView(requireContext()).apply {
            keepScreenOn = true
        }
        previewContainer.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return previewContainer
    }

    override fun initView() {
        host?.onUsbCameraLog("UsbCameraFragment initView")
        super.initView()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                host?.onUsbCameraLog("UsbCameraFragment camera opened")
                previewReadySent = false
                if (!previewCallbackRegistered) {
                    addPreviewDataCallBack(this)
                    previewCallbackRegistered = true
                }
                host?.onUsbCameraOpened()
            }

            ICameraStateCallBack.State.CLOSED -> {
                host?.onUsbCameraLog("UsbCameraFragment camera closed")
                previewReadySent = false
                previewCallbackRegistered = false
                host?.onUsbCameraClosed("USB webcam disconnected.")
            }

            ICameraStateCallBack.State.ERROR -> {
                host?.onUsbCameraLog("UsbCameraFragment error: ${msg ?: "unknown error"}")
                previewReadySent = false
                previewCallbackRegistered = false
                host?.onUsbCameraError(msg ?: "unknown error")
            }
        }
    }

    override fun onPreviewData(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        val frame = data ?: return
        if (!previewReadySent) {
            previewReadySent = true
            host?.onUsbPreviewReady(width, height, format)
        }
        host?.onUsbPreviewFrame(frame, width, height, format)
    }

    override fun onDestroyView() {
        if (previewCallbackRegistered) {
            runCatching { removePreviewDataCallBack(this) }
            previewCallbackRegistered = false
        }
        super.onDestroyView()
    }

    fun requestFirstAvailableUsbCamera() {
        requestFirstAvailableUsbCamera(attempt = 0)
    }

    private fun requestFirstAvailableUsbCamera(attempt: Int) {
        if (isCameraOpened()) {
            host?.onUsbCameraLog("UsbCameraFragment requestFirstAvailableUsbCamera already opened")
            return
        }
        val device = getDeviceList()?.firstOrNull()
        host?.onUsbCameraLog(
            "UsbCameraFragment requestFirstAvailableUsbCamera attempt=$attempt device=${device?.deviceName ?: "none"}"
        )
        if (device != null) {
            val failed = requestPermission(device)
            host?.onUsbCameraLog(
                "UsbCameraFragment requestPermission attempt=$attempt failed=$failed device=${device.deviceName}"
            )
        }
        if (attempt >= REQUEST_RETRY_COUNT) {
            host?.onUsbCameraLog("UsbCameraFragment requestFirstAvailableUsbCamera giving up")
            return
        }
        previewContainer.postDelayed(
            { requestFirstAvailableUsbCamera(attempt + 1) },
            REQUEST_RETRY_DELAY_MS
        )
    }

    override fun getCameraView(): IAspectRatio = previewView

    override fun getCameraViewContainer(): ViewGroup = previewContainer

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(VIDEO_WIDTH)
            .setPreviewHeight(VIDEO_HEIGHT)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAspectRatioShow(true)
            .setRawPreviewData(true)
            .setCaptureRawImage(false)
            .create()
    }
}
