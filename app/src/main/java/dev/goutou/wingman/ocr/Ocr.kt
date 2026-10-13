package dev.goutou.wingman.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App 进程里的图片文字识别。
 *
 * 用的是 ML Kit 的**内置**中文模型（`com.google.mlkit:text-recognition-chinese`）：
 * 完全离线、不要 Play 服务、也不需要联网下模型 —— 代价只是 APK 里多一份模型和原生库。
 *
 * 为什么绕一圈回 App 里认：注入到微信里的那段代码**不能引 ML Kit**（模型 + 11MB 原生库太重，
 * 而且「注入侧只用系统 API + kotlin stdlib」是硬规矩 —— 引第三方库会和微信自带的同名类撞车）。
 * 所以图片走本地代理回到这里认（见 proxy/ProxyServer.kt 的 `/proxy/ocr`），只把**文字**还回去：
 * 图不落盘、不上传，认完就丢。
 *
 * 线程模型：代理那边是线程池里的一个工作线程，这里就**同步阻塞**等结果（最多 [DEFAULT_TIMEOUT_MS]）。
 * ML Kit 的完成回调默认落在主线程，而跑前台服务时主线程是活的，所以阻塞不会互相卡死。
 */
object Ocr {

    private const val DEFAULT_TIMEOUT_MS = 15_000L

    /** 同一个识别器只建一次（第一次调用会做一次初始化，几百毫秒）。 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 同一张图别重复认。
     *
     * 注入侧每一轮读屏都会把同一张图再送一次（微信重绘、列表滚动都会触发），
     * 而「同一张图」在字节层面是一模一样的 —— 拿 sha256 当键正好。
     * 只留最近 [CACHE_MAX] 张（按访问顺序 LRU）：聊天里同时在看的图没几张，够用又不占内存。
     */
    private const val CACHE_MAX = 32

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    /**
     * 认一张 JPEG。
     *
     * 返回识别到的文字；**空串是正常结果**（这张图里确实没有字，比如表情包）。
     * 识别失败**抛异常** —— 由代理那边包成 HTTP 错误码，微信侧看到非 200 就回落成图片占位。
     */
    @Synchronized
    fun recognize(image: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        if (image.isEmpty()) return ""
        val key = sha256(image)
        cache[key]?.let { return it }
        val text = runRecognize(image, timeoutMs)
        cache[key] = text
        return text
    }

    /** 缓存里现在有几张（诊断 / 单测用）。 */
    fun cachedCount(): Int = synchronized(cache) { cache.size }

    private fun runRecognize(image: ByteArray, timeoutMs: Long): String {
        // ⚠️ ML Kit 的 InputImage **不吃 JPEG 字节**（它只认 Bitmap / NV21 / YV12 / YUV_420_888，
        // 压根没有 IMAGE_FORMAT_JPEG 这个常量）—— 所以先自己解成位图再喂进去。
        // 进来的图已经被微信侧压到最长边 1280，解出来大约 10MB 上下，认完就回收。
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size)
            ?: throw IllegalStateException("这张图解不开（不是有效图片？）")
        // 太小的图**放大**再认（issue #7②）：微信列表里那张图可能只是缩略图，
        // 小字在两三百像素宽的位图上根本不成形 —— 而 OCR_MAX_SIDE 只缩不放，
        // 于是「对方发来的含字截图」会稳定地回一句「这张图里没字」。
        val bitmap = upscaleIfSmall(decoded)

        val input = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        var text: String? = null
        var failure: Throwable? = null

        recognizer.process(input)
            .addOnSuccessListener { result ->
                // 用整段文本再切行：块 / 行 / 词那棵树对「一条消息」来说太细了，行才是人读的单位
                text = ocrText(result.text.split('\n'))
                latch.countDown()
            }
            .addOnFailureListener { t ->
                failure = t
                latch.countDown()
            }

        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        // 超时就**别回收**：ML Kit 那边的任务可能还在读这张位图，回收了会崩 —— 交给 GC 慢慢收
        if (finished) runCatching { bitmap.recycle() }
        if (!finished) throw IllegalStateException("识别超时（${timeoutMs}ms）")
        failure?.let { throw IllegalStateException("识别失败：${it.message}", it) }
        return text.orEmpty()
    }

    /**
     * 长边小于 [UPSCALE_BELOW] 的图先放大再认。
     *
     * 放大不会凭空造出信息，但它让笔画重新跨过识别器需要的那几个像素 ——
     * 对「微信列表里那张缩略图」这种尺寸（两三百像素）帮助很明显。
     * 上限 [UPSCALE_MAX_LONGEST] 是防止把一张中等的图放大到几百兆内存。
     */
    private fun upscaleIfSmall(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= 0 || longest >= UPSCALE_BELOW) return src
        val k = (UPSCALE_MAX_LONGEST.toFloat() / longest).coerceAtMost(2f)
        if (k <= 1.05f) return src
        val out = runCatching {
            Bitmap.createScaledBitmap(
                src,
                (src.width * k).toInt().coerceAtLeast(1),
                (src.height * k).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull() ?: return src
        // 只回收「被换掉的那一份」：createScaledBitmap 尺寸相同时会把原图原样还回来
        if (out !== src) runCatching { src.recycle() }
        return out
    }

    /** 长边小于这个值就先放大（见 [upscaleIfSmall]）。 */
    private const val UPSCALE_BELOW = 900

    /** 放大的上限，别把中等图放大到爆内存。 */
    private const val UPSCALE_MAX_LONGEST = 1800

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
