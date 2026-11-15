package org.thunderdog.challegram.kuikly

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRLogAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRThreadAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRUncaughtExceptionHandlerAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "KuiklyAdapter"

/** 日志适配器：桥接到 logcat。 */
object KuiklyLogAdapter : IKRLogAdapter {
  override val asyncLogEnable: Boolean
    get() = true

  override fun i(tag: String, msg: String) {
    Log.i(tag, msg)
  }

  override fun d(tag: String, msg: String) {
    Log.d(tag, msg)
  }

  override fun e(tag: String, msg: String) {
    Log.e(tag, msg)
  }
}

/** 线程适配器：Kuikly 不自建线程池，复用宿主的子线程。 */
class KuiklyThreadAdapter : IKRThreadAdapter {
  override fun executeOnSubThread(task: () -> Unit) {
    subThreadPoolExecutor.execute(task)
  }
}

private val subThreadPoolExecutor by lazy {
  Executors.newFixedThreadPool(2)
}

/** 路由适配器：Kuikly 页面之间的跳转统一走 [KuiklyActivity]。 */
object KuiklyRouterAdapter : IKRRouterAdapter {
  override fun openPage(context: Context, pageName: String, pageData: org.json.JSONObject) {
    KuiklyActivity.start(context, pageName, JSONObject(pageData.toString()))
  }

  override fun closePage(context: Context) {
    (context as? Activity)?.finish()
  }
}

/** 异常适配器：Kuikly 业务逻辑异常统一收集，避免拖垮宿主进程。 */
object KuiklyCrashAdapter : IKRUncaughtExceptionHandlerAdapter {
  override fun uncaughtException(throwable: Throwable) {
    Log.e(TAG, "Kuikly error: ${throwable.stackTraceToString()}")
  }
}

/**
 * 图片适配器：为 Kuikly `Image` 组件提供 http(s)/file/assets/base64 解码能力。
 * 无第三方图片库依赖，位图解码在子线程执行，结果按 src 维度做内存缓存。
 */
class KuiklyImageAdapter(private val appContext: Context) : IKRImageAdapter {

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  private val memoryCache = object : LruCache<String, Drawable>(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
  ) {
    override fun sizeOf(key: String, value: Drawable): Int {
      val bitmap = (value as? BitmapDrawable)?.bitmap
      return bitmap?.byteCount?.div(1024) ?: 1
    }
  }

  override fun fetchDrawable(
    imageLoadOption: HRImageLoadOption,
    callback: (drawable: Drawable?) -> Unit
  ) {
    val src = imageLoadOption.src
    memoryCache.get(src)?.let {
      callback(it)
      return
    }
    subThreadPoolExecutor.execute {
      val drawable = decode(imageLoadOption)
      if (drawable != null) {
        memoryCache.put(src, drawable)
      }
      // 回调线程由渲染层保证切换，参考官方 demo 的 base64 分支实现。
      callback(drawable)
    }
  }

  private fun decode(option: HRImageLoadOption): Drawable? {
    return try {
      val bitmap = when {
        option.isBase64() -> decodeBase64(option)
        option.isAssets() -> decodeAssets(option.src.substring(HRImageLoadOption.SCHEME_ASSETS.length))
        option.isFile() -> decodeFile(option.src.substring(HRImageLoadOption.SCHEME_FILE.length))
        option.isWebUrl() -> decodeWebUrl(option.src)
        else -> null
      } ?: return null
      BitmapDrawable(appContext.resources, bitmap)
    } catch (t: Throwable) {
      Log.e(TAG, "decode failed for ${option.src}", t)
      null
    }
  }

  private fun decodeBase64(option: HRImageLoadOption): Bitmap? {
    val commaIndex = option.src.indexOf(',')
    val data = if (commaIndex >= 0) option.src.substring(commaIndex + 1) else option.src
    val bytes = Base64.decode(data, Base64.DEFAULT)
    return decodeBytes(bytes, option)
  }

  private fun decodeAssets(assetPath: String): Bitmap? {
    appContext.assets.open(assetPath).use { input ->
      return decodeBytes(input.readBytes(), null)
    }
  }

  private fun decodeFile(path: String): Bitmap? {
    val trimmed = if (path.startsWith("///")) path.drop(2) else path
    val file = File(trimmed)
    if (!file.exists()) return null
    return decodeBytes(file.readBytes(), null)
  }

  private fun decodeWebUrl(src: String): Bitmap? {
    val connection = (URL(src).openConnection() as HttpURLConnection).apply {
      connectTimeout = 10_000
      readTimeout = 10_000
      setRequestProperty("User-Agent", "Telegram-X-Kuikly/1.0")
    }
    try {
      connection.inputStream.use { input ->
        val bytes = ByteArrayOutputStream().use { buffer ->
          input.copyTo(buffer)
          buffer.toByteArray()
        }
        return decodeBytes(bytes, null)
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun decodeBytes(bytes: ByteArray, option: HRImageLoadOption?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inJustDecodeBounds = false
      if (option != null && option.needResize && bounds.outWidth > 0 && bounds.outHeight > 0) {
        inSampleSize = calculateInSampleSize(bounds, option.requestWidth, option.requestHeight)
      }
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  private fun calculateInSampleSize(bounds: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    if (reqWidth <= 0 || reqHeight <= 0) return 1
    var inSampleSize = 1
    var height = bounds.outHeight
    var width = bounds.outWidth
    while (height / 2 >= reqHeight || width / 2 >= reqWidth) {
      height /= 2
      width /= 2
      inSampleSize *= 2
    }
    return inSampleSize
  }
}
