package com.github.sikv.photos.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.IBinder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.sikv.photos.App
import com.github.sikv.photos.R
import com.github.sikv.photos.enumeration.DownloadPhotoState
import com.github.sikv.photos.util.savePhotoInFile
import com.github.sikv.photos.util.savePhotoUri
import kotlinx.coroutines.*
import javax.inject.Inject

class DownloadPhotoService : Service() {

    companion object {
        private const val ACTION_DOWNLOAD_PHOTO = "action_download_photo"
        private const val ACTION_CANCEL = "action_cancel"

        private const val EXTRA_PHOTO_URL = "extra_photo_url"

        fun startServiceActionDownload(context: Context, photoUrl: String) {
            val intent = Intent(context, DownloadPhotoService::class.java)

            intent.action = ACTION_DOWNLOAD_PHOTO
            intent.putExtra(EXTRA_PHOTO_URL, photoUrl)

            context.startService(intent)
        }

        fun startServiceActionCancel(context: Context) {
            val intent = Intent(context, DownloadPhotoService::class.java)

            intent.action = ACTION_CANCEL

            context.startService(intent)
        }
    }

    private lateinit var job: Job

    @Inject
    lateinit var glide: RequestManager

    init {
        App.instance.appComponent.inject(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD_PHOTO -> {
                downloadAndSavePhoto(intent.getStringExtra(EXTRA_PHOTO_URL))
            }

            ACTION_CANCEL -> {
                cancel()
            }
        }

        return START_NOT_STICKY
    }

    private fun cancel() {
        CoroutineScope(Dispatchers.Main).launch {
            postMessage(getString(R.string.canceling))
            updateDownloadPhotoState(DownloadPhotoState.CANCELING)

            job.cancelAndJoin()

            postMessage(getString(R.string.photo_downloading_canceled))
            updateDownloadPhotoState(DownloadPhotoState.CANCELED)
        }
    }

    private fun downloadAndSavePhoto(photoUrl: String?) {
        postMessage(getString(R.string.downloading_photo))
        updateDownloadPhotoState(DownloadPhotoState.DOWNLOADING_PHOTO)

        glide.asBitmap()
                .load(photoUrl)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                        job = CoroutineScope(Dispatchers.Main).launch {
                            savePhoto(bitmap)?.let { uri ->
                                this@DownloadPhotoService.savePhotoUri(uri)

                                postMessage(getString(R.string.photo_ready))
                                updateDownloadPhotoState(DownloadPhotoState.PHOTO_READY)

                            } ?: run {
                                postMessage(getString(R.string.error_downloading_photo))
                                updateDownloadPhotoState(DownloadPhotoState.ERROR_DOWNLOADING_PHOTO)
                            }
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)

                        postMessage(getString(R.string.error_downloading_photo))
                        updateDownloadPhotoState(DownloadPhotoState.ERROR_DOWNLOADING_PHOTO)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) { }
                })
    }

    private suspend fun savePhoto(photo: Bitmap): Uri? {
        return withContext(Dispatchers.IO) {
            this@DownloadPhotoService.savePhotoInFile(photo)
        }
    }

    private fun postMessage(message: String) {
        App.instance.postMessage(message)
    }

    private fun updateDownloadPhotoState(state: DownloadPhotoState) {
        App.instance.postDownloadPhotoState(state)
    }
}