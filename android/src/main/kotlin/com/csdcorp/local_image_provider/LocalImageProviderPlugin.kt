package com.csdcorp.local_image_provider

import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.load.engine.GlideException
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

enum class LocalImageProviderErrors {
  imgLoadFailed,
  imgNotFound,
  missingOrInvalidArg,
  multipleRequests,
  missingOrInvalidImage,
  noActivity,
  unimplemented
}

const val pluginChannelName = "plugin.csdcorp.com/local_image_provider"

/** LocalImageProviderPlugin */
public class LocalImageProviderPlugin: FlutterPlugin, MethodCallHandler,
        PluginRegistry.RequestPermissionsResultListener, ActivityAware {
  private var pluginContext: Context? = null
  private var currentActivity: Activity? = null
  private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SZZZZZ")
  private val minSdkForImageSupport = 8
  private val imagePermissionCode = 34264
  private var activeResult: Result? = null
  private var initializedSuccessfully: Boolean = false
  private var permissionGranted: Boolean = false
  private val imageColumns = arrayOf(MediaStore.Images.ImageColumns.DISPLAY_NAME,
          MediaStore.Images.ImageColumns.DATE_TAKEN,
          MediaStore.Images.ImageColumns.TITLE,
          MediaStore.Images.ImageColumns.HEIGHT,
          MediaStore.Images.ImageColumns.WIDTH,
          MediaStore.MediaColumns._ID)

  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    onAttachedToEngine(flutterPluginBinding.getApplicationContext(), flutterPluginBinding.getBinaryMessenger())
  }

  // This static function is optional and equivalent to onAttachedToEngine. It supports the old
  // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
  // plugin registration via this function while apps migrate to use the new Android APIs
  // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
  //
  // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
  // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
  // depending on the user's project. onAttachedToEngine or registerWith must both be defined
  // in the same class.
  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val imagePlugin = LocalImageProviderPlugin()
      imagePlugin.currentActivity = registrar.activity()
      registrar.addRequestPermissionsResultListener(imagePlugin)
      imagePlugin.onAttachedToEngine(registrar.context(), registrar.messenger())
    }
  }

  private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
    this.pluginContext = applicationContext
    channel = MethodChannel(messenger, pluginChannelName)
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    this.pluginContext = null
    channel.setMethodCallHandler(null)
  }

  override fun onDetachedFromActivity() {
    currentActivity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    currentActivity = binding.activity
    binding.addRequestPermissionsResultListener(this)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    currentActivity = binding.activity
    binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    currentActivity = null
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
    val result = ChannelResultWrapper(rawResult)
    when (call.method) {
      "initialize" -> initialize(result)
      "has_permission" -> hasPermission(result)
      "latest_images" -> {
        if (null != call.arguments && call.arguments is Int) {
          val maxResults = call.arguments as Int
          getLatestImages(maxResults, result)
        } else {
          result.error(LocalImageProviderErrors.missingOrInvalidArg.name,
                  "Missing arg maxPhotos", null)
        }
      }
      "albums" -> {
        if (null != call.arguments && call.arguments is Int) {
          val localAlbumType = call.arguments as Int
          getAlbums(localAlbumType, result)
        } else {
          result.error(LocalImageProviderErrors.missingOrInvalidArg.name,
                  "Missing arg albumType", null)
        }
      }
      "image_bytes" -> {
        val id = call.argument<String>("id")
        val width = call.argument<Int>("pixelWidth")
        val height = call.argument<Int>("pixelHeight")
        if (id != null && width != null && height != null) {
          getImageBytes(id, width, height, result)
        } else {
          result.error(LocalImageProviderErrors.missingOrInvalidArg.name,
                  "Missing arg requires id, width, height", null)
        }
      }
      "images_in_album" -> {
        val albumId = call.argument<String>("albumId")
        val maxImages = call.argument<Int>("maxImages")
        if (albumId != null && maxImages != null) {
          findImagesInAlbum(albumId, maxImages, result)
        } else {
          result.error(LocalImageProviderErrors.missingOrInvalidArg.name,
                  "Missing arg requires albumId, maxImages", null)
        }
      }
      else -> result.notImplemented()
    }
  }

  private fun hasPermission(result: Result) {
    if (sdkVersionTooLow(result)) {
      return
    }
    var hasPerm = false
    val localContext = pluginContext
    if (localContext != null) {
      hasPerm = ContextCompat.checkSelfPermission(localContext,
              Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    result.success(hasPerm)
  }

  private fun initialize(result: Result) {
    if (sdkVersionTooLow(result)) {
      return
    }
    if (null != activeResult) {
      result.error(LocalImageProviderErrors.multipleRequests.name,
              "Only one initialize at a time", null)
      return
    }
    activeResult = result
    val localContext = pluginContext
    initializeIfPermitted(localContext)
  }

  private fun sdkVersionTooLow(result: Result): Boolean {
    if (Build.VERSION.SDK_INT < minSdkForImageSupport) {
      result.success(false)
      return true
    }
    return false
  }

  private fun isNotInitialized(result: Result): Boolean {
    if (!initializedSuccessfully) {
      result.success(false)
    }
    return !initializedSuccessfully
  }


  private fun getAlbums(localAlbumType: Int, result: Result) {
    if (isNotInitialized(result)) {
      return
    }
    val albums = ArrayList<String>()
    Thread(Runnable {
      albums.addAll(getAlbumsFromLocation(MediaStore.Images.Media.INTERNAL_CONTENT_URI))
      albums.addAll(getAlbumsFromLocation(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
      result.success(albums)
    }).start()

  }

  private fun getAlbumsFromLocation(imgUri: Uri): ArrayList<String> {
    val mediaColumns = arrayOf(
            "DISTINCT " + MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Images.ImageColumns.BUCKET_ID
    )
    val sortOrder = "${MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME} ASC"
    val albums = ArrayList<String>()
    val localActivity = currentActivity
    if (null != localActivity) {
    val mediaResolver = localActivity.contentResolver
    val imageCursor = mediaResolver.query(imgUri, mediaColumns, null,
            null, sortOrder)
    imageCursor?.use {
      val titleColumn = imageCursor.getColumnIndexOrThrow(
              MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
      val idColumn = imageCursor.getColumnIndexOrThrow(
              MediaStore.Images.ImageColumns.BUCKET_ID)
      while (imageCursor.moveToNext()) {
        val bucketId = imageCursor.getString(idColumn)
        val imageCount = getAlbumImageCount(bucketId, imgUri)
        val imgJson = JSONObject()
        imgJson.put("title", imageCursor.getString(titleColumn))
        imgJson.put("imageCount", imageCount)
        imgJson.put("id", bucketId)
        imgJson.put("coverImg", getAlbumsCoverImage(bucketId, imgUri))
        albums.add(imgJson.toString())
      }
    }
    }
    return albums
  }

  private fun getAlbumsCoverImage(bucketId: String, imgUri: Uri): JSONObject {
    var imgJson = JSONObject()
    val mediaColumns = arrayOf(
            MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.Images.ImageColumns.DISPLAY_NAME,
            MediaStore.Images.ImageColumns.TITLE,
            MediaStore.Images.ImageColumns.HEIGHT,
            MediaStore.Images.ImageColumns.WIDTH
    )
    val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC LIMIT 1"
    val selection = "${MediaStore.Images.ImageColumns.BUCKET_ID} = ?"
    val selectionArgs = arrayOf(bucketId)
    val localActivity = currentActivity
    if (null != localActivity) {
    val mediaResolver = localActivity.contentResolver
    val imageCursor = mediaResolver.query(imgUri, mediaColumns, selection,
            selectionArgs, sortOrder)
    imageCursor?.use {
      val widthColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.WIDTH)
      val heightColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.HEIGHT)
      val dateColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
      val titleColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.TITLE)
      val idColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)
      while (imageCursor.moveToNext()) {
        imgJson = imageToJson(
                imageCursor.getString(titleColumn),
                imageCursor.getInt(heightColumn),
                imageCursor.getInt(widthColumn),
                imageCursor.getString(idColumn),
                Date(imageCursor.getLong(dateColumn))
        )
      }
    }
    }
    return imgJson
  }

  private fun getAlbumImageCount(bucketId: String, imgUri: Uri): Int {
    var imageCount = 0
    val mediaColumns = arrayOf(
            MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.BUCKET_ID
    )
    val selection = "${MediaStore.Images.ImageColumns.BUCKET_ID} = ?"
    val selectionArgs = arrayOf(bucketId)
    val localActivity = currentActivity
    if (null != localActivity) {
      val mediaResolver = localActivity.contentResolver
      val imageCursor = mediaResolver.query(imgUri, mediaColumns, selection,
              selectionArgs, null)
      imageCursor?.use {
        imageCount = imageCursor.count
      }
    }
    return imageCount
  }

  private fun getLatestImages(maxResults: Int, result: Result) {
    if (isNotInitialized(result)) {
      return
    }
    Thread(Runnable {
      val localActivity = currentActivity
      if (null != localActivity) {
        val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC LIMIT $maxResults"
        val mediaResolver = localActivity.contentResolver
        val images = findImagesToJson(mediaResolver, imgUri, null, null, sortOrder)
        result.success(images)
      } else {
        result.error(LocalImageProviderErrors.noActivity.name,
                "This method requires an activity", null)
      }
    }).start()
  }

  private fun findImagesInAlbum(albumId: String, maxImages: Int, result: Result) {
    if (isNotInitialized(result)) {
      return
    }
    Thread(Runnable {
      val localActivity = currentActivity
      if (null != localActivity) {
        val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC LIMIT $maxImages"
        val selection = "${MediaStore.Images.ImageColumns.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(albumId)
        val mediaResolver = localActivity.contentResolver
        val images = findImagesToJson(mediaResolver, imgUri, selection, selectionArgs, sortOrder)
        result.success(images)
      } else {
        result.error(LocalImageProviderErrors.noActivity.name,
                "This method requires an activity", null)
      }
    }).start()
  }

  private fun findImagesToJson(mediaResolver: ContentResolver, imgUri: Uri, selection: String?,
                               selectionArgs: Array<String>?, sortOrder: String? ):
          ArrayList<String> {
    val images = ArrayList<String>()
    val imageCursor = mediaResolver.query(imgUri, imageColumns, selection,
            selectionArgs, sortOrder)
    imageCursor?.use {
      val widthColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.WIDTH)
      val heightColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.HEIGHT)
      val dateColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
      val titleColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.TITLE)
      val idColumn = imageCursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)
      while (imageCursor.moveToNext()) {
        val imgJson = imageToJson(
                imageCursor.getString(titleColumn),
                imageCursor.getInt(heightColumn),
                imageCursor.getInt(widthColumn),
                imageCursor.getString(idColumn),
                Date(imageCursor.getLong(dateColumn))
        )
        images.add(imgJson.toString())
      }
    }
    return images
  }

  private fun imageToJson( title: String, height: Int, width: Int, id: String, takenOn: Date ) : JSONObject {
    val imgJson = JSONObject()
    imgJson.put("title", title)
    imgJson.put("pixelWidth", width )
    imgJson.put("pixelHeight",height )
    imgJson.put("id", id )
    val isoDate = isoFormatter.format(takenOn)
    imgJson.put("creationDate", isoDate)
    return imgJson
  }

  private fun getImageBytes(id: String, width: Int, height: Int, result: Result) {
    if (isNotInitialized(result)) {
      return
    }
    Thread(Runnable {
      val imgUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
      try {
        val localActivity = currentActivity
        if (null != localActivity) {
        val bitmapLoad = GlideApp.with(localActivity)
                .asBitmap()
                .load(imgUri)
                .override(width, height)
                .fitCenter()
                .submit()
        val bitmap = bitmapLoad.get()
        val jpegBytes = ByteArrayOutputStream()
        jpegBytes.use {
          bitmap.compress(Bitmap.CompressFormat.JPEG, 70, jpegBytes)
          result.success(jpegBytes.toByteArray())
        }
      } else {
          result.error(LocalImageProviderErrors.noActivity.name,
                  "This method requires an activity", null)

        }
      }
      catch ( glideExc: Exception ) {
        if ( glideExc is GlideException ||
                glideExc is FileNotFoundException ||
                glideExc.cause is GlideException ||
                glideExc.cause is FileNotFoundException ) {
          result.error(
                  LocalImageProviderErrors.missingOrInvalidImage.name,
                  "Missing image", id )
        }
        else {
          result.error(
                  LocalImageProviderErrors.imgLoadFailed.name,
                  "Exception while loading image", glideExc.localizedMessage )
        }
      }
    }).start()
  }

  private fun initializeIfPermitted(context: Context?) {
    if (null == context) {
      completeInitialize()
      return
    }
    permissionGranted = ContextCompat.checkSelfPermission(context,
            Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    if (!permissionGranted) {
      val localActivity = currentActivity
      if (null != localActivity) {
        ActivityCompat.requestPermissions(localActivity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), imagePermissionCode)
      } else {
        completeInitialize()
      }
    } else {
      completeInitialize()
    }
  }

  private fun completeInitialize() {

    initializedSuccessfully = permissionGranted
    activeResult?.success(permissionGranted)
    activeResult = null
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?,
                                          grantResults: IntArray?): Boolean {
    when (requestCode) {
      imagePermissionCode -> {
        if (null != grantResults) {
          permissionGranted = grantResults.isNotEmpty() &&
                  grantResults[0] == PackageManager.PERMISSION_GRANTED
        }
        completeInitialize()
        return true
      }
    }
    return false
  }
}

private class ChannelResultWrapper(result: Result) : Result {
  // Caller handler
  val handler: Handler = Handler(Looper.getMainLooper())
  val result: Result = result

  // make sure to respond in the caller thread
  override fun success(results: Any?) {

    handler.post {
      run {
        result.success(results)
      }
    }
  }

  override fun error(errorCode: String?, errorMessage: String?, data: Any?) {
    handler.post {
      run {
        result.error(errorCode, errorMessage, data)
      }
    }
  }

  override fun notImplemented() {
    handler.post {
      run {
        result.notImplemented()
      }
    }
  }
}
