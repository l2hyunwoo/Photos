package com.github.sikv.photos.viewmodel

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.github.sikv.photos.App
import com.github.sikv.photos.api.ApiClient
import com.github.sikv.photos.data.Event
import com.github.sikv.photos.database.FavoritesDao
import com.github.sikv.photos.database.PhotoData
import com.github.sikv.photos.model.PexelsPhoto
import com.github.sikv.photos.model.Photo
import com.github.sikv.photos.model.UnsplashPhoto
import com.github.sikv.photos.util.CustomWallpaperManager
import com.github.sikv.photos.util.Utils
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import javax.inject.Inject
import kotlin.properties.Delegates

class PhotoViewModel(
        application: Application,
        private var photo: Photo,
        private val favoritesDataSource: FavoritesDao

) : AndroidViewModel(application) {

    private var viewModelJob = Job()

    private val uiScope = CoroutineScope(Dispatchers.Main + viewModelJob)

    @Inject
    lateinit var glide: RequestManager

    private var favorited: Boolean by Delegates.observable(false) { _, _, newValue ->
        favoriteChangedEvent.value = Event(newValue)
    }

    var favoriteChangedEvent: MutableLiveData<Event<Boolean>>
        private set

    var photoReadyEvent: MutableLiveData<Event<Photo?>>
        private set

    init {
        photoReadyEvent = MutableLiveData()
        favoriteChangedEvent = MutableLiveData()

        App.instance.glideComponent.inject(this)

        initFavorited()
    }

    override fun onCleared() {
        super.onCleared()

        viewModelJob.cancel()
    }

    fun loadPhoto(): LiveData<Event<Bitmap>> {
        val photoLoadedEvent = MutableLiveData<Event<Bitmap>>()
        var photoLoaded = false

        fun loadFullSizePhoto() {
            glide.asBitmap()
                    .load(photo.getNormalUrl())
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                            photoLoadedEvent.value = Event(bitmap)
                            photoLoaded = true
                        }
                    })
        }

        if (photo.isLocalPhoto()) {
            glide.asBitmap()
                    .load(photo.getSmallUrl())
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                            photoLoadedEvent.value = Event(bitmap)
                        }
                    })

            when (photo.getSource()) {
                UnsplashPhoto.SOURCE -> {
                    ApiClient.INSTANCE.unsplashClient.getPhoto(photo.getPhotoId())
                            .enqueue(object : Callback<UnsplashPhoto> {
                                override fun onFailure(call: Call<UnsplashPhoto>, t: Throwable) {
                                }

                                override fun onResponse(call: Call<UnsplashPhoto>, response: Response<UnsplashPhoto>) {
                                    response.body()?.let { unsplashPhoto ->
                                        this@PhotoViewModel.photo = unsplashPhoto
                                        loadFullSizePhoto()

                                        photoReadyEvent.value = Event(unsplashPhoto)
                                    }
                                }
                            })
                }

                PexelsPhoto.SOURCE -> {
                    ApiClient.INSTANCE.pexelsClient.getPhoto(photo.getPhotoId())
                            .enqueue(object : Callback<PexelsPhoto> {
                                override fun onFailure(call: Call<PexelsPhoto>, t: Throwable) {
                                }

                                override fun onResponse(call: Call<PexelsPhoto>, response: Response<PexelsPhoto>) {
                                    response.body()?.let { pexelsPhoto ->
                                        this@PhotoViewModel.photo = pexelsPhoto
                                        loadFullSizePhoto()

                                        photoReadyEvent.value = Event(pexelsPhoto)

                                    }
                                }
                            })
                }
            }

        } else {
            glide.asBitmap()
                    .load(photo.getNormalUrl())
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onLoadStarted(placeholder: Drawable?) {
                            glide.asBitmap()
                                    .load(photo.getSmallUrl())
                                    .into(object : SimpleTarget<Bitmap>() {
                                        override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                                            if (!photoLoaded) {
                                                photoLoadedEvent.value = Event(bitmap)
                                            }
                                        }
                                    })
                        }

                        override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                            photoLoadedEvent.value = Event(bitmap)
                            photoLoaded = true
                        }
                    })

            photoReadyEvent.value = Event(photo)
        }

        return photoLoadedEvent
    }

    fun favorite() {
        favorited = !favorited

        val photoData = PhotoData(photo.getPhotoId(), photo.getSmallUrl(), photo.getSource())

        GlobalScope.launch {
            if (favorited) {
                favoritesDataSource.insert(photoData)
            } else {
                favoritesDataSource.delete(photoData)
            }
        }
    }

    fun createShareIntent(): Intent {
        val shareIntent = Intent()

        shareIntent.action = Intent.ACTION_SEND
        shareIntent.putExtra(Intent.EXTRA_TEXT, photo.getShareUrl())
        shareIntent.type = "text/plain"

        return shareIntent
    }

    fun openAuthorUrl() {
        photo.getPhotographerUrl()?.let {
            Utils.openUrl(getApplication(), it)
        }
    }

    fun openPhotoSource() {
        Utils.openUrl(getApplication(), photo.getSourceUrl())
    }

    @TargetApi(Build.VERSION_CODES.N)
    fun setWallpaper(activity: Activity, which: CustomWallpaperManager.Which) {
        CustomWallpaperManager.setWallpaper(activity, photo.getLargeUrl(), which)
    }

    private fun initFavorited() {
        uiScope.launch {
            favorited = getPhotoFromFavoritesDatabase() != null
        }
    }

    private suspend fun getPhotoFromFavoritesDatabase(): Photo? {
        return withContext(Dispatchers.IO) {
            favoritesDataSource.get(photo.getPhotoId())
        }
    }
}