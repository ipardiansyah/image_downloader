package com.ko2ic.imagedownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.*

class ImageDownloaderPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {

    companion object {
        private const val CHANNEL = "plugins.ko2ic.com/image_downloader"
        private const val LOGGER_TAG = "image_downloader"
    }

    private lateinit var channel: MethodChannel
    private var applicationContext: Context? = null
    private var activity: Activity? = null
    private var permissionListener: ImageDownloaderPermissionListener? = null
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var inPublicDir: Boolean = true
    private var callback: CallbackImpl? = null

    // === FlutterPlugin lifecycle ===
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        setupChannel(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        tearDown()
        pluginBinding = null
    }

    // === ActivityAware lifecycle ===
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        permissionListener = ImageDownloaderPermissionListener(activity!!)
        activityBinding?.addRequestPermissionsResultListener(permissionListener!!)
    }

    override fun onDetachedFromActivity() {
        tearDownActivity()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        tearDownActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    // === Setup and teardown ===
    private fun setupChannel(messenger: BinaryMessenger, context: Context) {
        applicationContext = context
        channel = MethodChannel(messenger, CHANNEL)
        channel.setMethodCallHandler(this)
    }

    private fun tearDown() {
        channel.setMethodCallHandler(null)
        applicationContext = null
    }

    private fun tearDownActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener!!)
        activityBinding = null
        activity = null
        permissionListener = null
    }

    // === Handle method calls ===
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "downloadImage" -> {
                inPublicDir = call.argument<Boolean>("inPublicDir") ?: true
                val ctx = applicationContext ?: return
                val permissionCallback = CallbackImpl(call, result, channel, ctx)
                this.callback = permissionCallback

                if (inPublicDir) {
                    permissionListener?.callback = permissionCallback
                    if (permissionListener?.alreadyGranted() == true) {
                        permissionCallback.granted()
                    }
                } else {
                    permissionCallback.granted()
                }
            }
            "cancel" -> callback?.downloader?.cancel()
            "open" -> open(call, result)
            "findPath" -> result.success(findPath(call))
            "findName" -> result.success(findName(call))
            "findByteSize" -> result.success(findByteSize(call))
            "findMimeType" -> result.success(findMimeType(call))
            else -> result.notImplemented()
        }
    }

    private fun findPath(call: MethodCall): String? {
        val imageId = call.argument<String>("imageId") ?: return null
        return applicationContext?.let { findFileData(imageId, it).path }
    }

    private fun findName(call: MethodCall): String? {
        val imageId = call.argument<String>("imageId") ?: return null
        return applicationContext?.let { findFileData(imageId, it).name }
    }

    private fun findByteSize(call: MethodCall): Int? {
        val imageId = call.argument<String>("imageId") ?: return null
        return applicationContext?.let { findFileData(imageId, it).byteSize }
    }

    private fun findMimeType(call: MethodCall): String? {
        val imageId = call.argument<String>("imageId") ?: return null
        return applicationContext?.let { findFileData(imageId, it).mimeType }
    }

    private fun open(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path") ?: return
        val ctx = applicationContext ?: return

        val file = File(path)
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
        val intent = Intent(Intent.ACTION_VIEW)

        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.image_downloader.provider", file
            )
        } else {
            Uri.fromFile(file)
        }

        intent.setDataAndType(uri, mimeType)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

        val manager = ctx.packageManager
        if (manager.queryIntentActivities(intent, 0).isEmpty()) {
            result.error("preview_error", "This file is not supported for previewing", null)
        } else {
            ctx.startActivity(intent)
        }
    }

    // === Query media or temporary DB ===
    @SuppressLint("Range")
    private fun findFileData(imageId: String, context: Context): FileData {
        return if (inPublicDir) {
            val contentResolver = context.contentResolver
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(imageId),
                null
            ).use {
                checkNotNull(it) { "$imageId is invalid." }
                it.moveToFirst()
                FileData(
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA)),
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)),
                    it.getInt(it.getColumnIndex(MediaStore.Images.Media.SIZE)),
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
                )
            }
        } else {
            val db = TemporaryDatabase(context).readableDatabase
            db.query(
                TemporaryDatabase.TABLE_NAME,
                TemporaryDatabase.COLUMNS,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(imageId),
                null, null, null
            ).use {
                it.moveToFirst()
                FileData(
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA)),
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)),
                    it.getInt(it.getColumnIndex(MediaStore.Images.Media.SIZE)),
                    it.getString(it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
                )
            }
        }
    }

    data class FileData(
        val path: String,
        val name: String,
        val byteSize: Int,
        val mimeType: String
    )

    // === Callback implementation ===
    class CallbackImpl(
        private val call: MethodCall,
        private val result: MethodChannel.Result,
        private val channel: MethodChannel,
        private val context: Context
    ) : ImageDownloaderPermissionListener.Callback {

        var downloader: Downloader? = null

        override fun granted() {
            val url = call.argument<String>("url") ?: return
            val headers: Map<String, String>? = call.argument("headers")
            val outputMimeType = call.argument<String>("mimeType")
            val inPublicDir = call.argument<Boolean>("inPublicDir") ?: true
            val directoryType = call.argument<String>("directory") ?: "DIRECTORY_DOWNLOADS"
            val subDirectory = call.argument<String>("subDirectory")
            val tempSubDir = subDirectory ?: SimpleDateFormat(
                "yyyy-MM-dd.HH.mm.sss", Locale.getDefault()
            ).format(Date())
            val directory = convertToDirectory(directoryType)

            val request = DownloadManager.Request(Uri.parse(url))
            headers?.forEach { (key, value) -> request.addRequestHeader(key, value) }
            if (inPublicDir) {
                request.setDestinationInExternalPublicDir(directory, tempSubDir)
            } else {
                TemporaryDatabase(context).writableDatabase.delete(TemporaryDatabase.TABLE_NAME, null, null)
                request.setDestinationInExternalFilesDir(context, directory, tempSubDir)
            }

            val downloader = Downloader(context, request)
            this.downloader = downloader
            downloader.execute(onNext = {
                if (it is Downloader.DownloadStatus.Running) {
                    val args = hashMapOf(
                        "image_id" to it.result.id.toString(),
                        "progress" to it.progress
                    )
                    Handler(Looper.getMainLooper()).post {
                        channel.invokeMethod("onProgressUpdate", args)
                    }
                }
            }, onError = {
                result.error(it.code, it.message, null)
            }, onComplete = {
                val baseDir = if (inPublicDir)
                    Environment.getExternalStoragePublicDirectory(directory)
                else
                    context.getExternalFilesDir(directory)
                val file = File(baseDir, tempSubDir)
                if (!file.exists()) {
                    result.error("save_error", "File not saved: ${file.absolutePath}", null)
                    return@execute
                }

                val stream = BufferedInputStream(FileInputStream(file))
                val mimeType = outputMimeType ?: URLConnection.guessContentTypeFromStream(stream)
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                val fileName = subDirectory ?: "$tempSubDir.${extension ?: "jpg"}"
                val newFile = File(baseDir, fileName)
                file.renameTo(newFile)
                val imageId = saveToDatabase(newFile, mimeType ?: "", inPublicDir)
                result.success(imageId)
            })
        }

        override fun denied() {
            result.success(null)
        }

        private fun convertToDirectory(type: String): String {
            return when (type) {
                "DIRECTORY_DOWNLOADS" -> Environment.DIRECTORY_DOWNLOADS
                "DIRECTORY_PICTURES" -> Environment.DIRECTORY_PICTURES
                "DIRECTORY_DCIM" -> Environment.DIRECTORY_DCIM
                "DIRECTORY_MOVIES" -> Environment.DIRECTORY_MOVIES
                else -> type
            }
        }

        @SuppressLint("Range")
        private fun saveToDatabase(file: File, mimeType: String, inPublicDir: Boolean): String {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.ImageColumns.DISPLAY_NAME, file.name)
                put(MediaStore.Images.ImageColumns.SIZE, file.length())
            }

            return if (inPublicDir) {
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
                    "${MediaStore.Images.Media.DATA}=?",
                    arrayOf(file.absolutePath),
                    null
                ).use {
                    it!!.moveToFirst()
                    it.getString(it.getColumnIndex(MediaStore.Images.Media._ID))
                }
            } else {
                val db = TemporaryDatabase(context)
                val id = UUID.randomUUID().toString()
                values.put(MediaStore.Images.Media._ID, id)
                db.writableDatabase.insert(TemporaryDatabase.TABLE_NAME, null, values)
                id
            }
        }
    }

    // === SQLite temp DB ===
    class TemporaryDatabase(context: Context) :
        SQLiteOpenHelper(context, TABLE_NAME, null, 1) {

        companion object {
            val COLUMNS = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.SIZE
            )
            const val TABLE_NAME = "image_downloader_temporary"
            private const val CREATE_SQL =
                "CREATE TABLE $TABLE_NAME (" +
                        "${MediaStore.Images.Media._ID} TEXT, " +
                        "${MediaStore.Images.Media.MIME_TYPE} TEXT, " +
                        "${MediaStore.Images.Media.DATA} TEXT, " +
                        "${MediaStore.Images.ImageColumns.DISPLAY_NAME} TEXT, " +
                        "${MediaStore.Images.ImageColumns.SIZE} INTEGER)"
        }

        override fun onCreate(db: SQLiteDatabase) = db.execSQL(CREATE_SQL)
        override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}
    }
}
