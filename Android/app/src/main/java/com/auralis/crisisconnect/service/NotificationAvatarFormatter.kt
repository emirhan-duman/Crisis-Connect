package com.auralis.crisisconnect.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Shader
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

internal object NotificationAvatarFormatter {
    fun resolveLocalProfileAvatarBitmap(context: Context): Bitmap? {
        val profileImage = runCatching {
            ProfileImageStorage.loadProfileImage(context.applicationContext)
        }.getOrNull() ?: return null
        return toCircularPhotoAvatar(context, profileImage)
    }

    fun resolveContactAvatarBitmap(
        context: Context,
        sessionCode: String,
        contactName: String
    ): Bitmap? {
        val savedAvatar = ContactAvatarStorage.loadContactAvatar(context.applicationContext, sessionCode)
        if (savedAvatar != null) {
            return toCircularPhotoAvatar(context, savedAvatar)
        }
        return generateInitialAvatarBitmap(context, sessionCode, contactName)
    }

    /**
     * Circular avatar for a remote (non-contact) sender — e.g. an authority-channel peer whose photo
     * came from their profile URL: the [photo] circle-cropped when given, else the same deterministic
     * colored-initial avatar contacts get ([seed] keeps the color stable per sender).
     */
    fun resolveRemoteAvatarBitmap(
        context: Context,
        seed: String,
        name: String,
        photo: Bitmap? = null
    ): Bitmap? {
        if (photo != null) toCircularPhotoAvatar(context, photo)?.let { return it }
        return generateInitialAvatarBitmap(context, seed, name)
    }

    private fun toCircularPhotoAvatar(context: Context, source: Bitmap): Bitmap? {
        if (source.width <= 0 || source.height <= 0) {
            return null
        }
        val density = context.resources.displayMetrics.density
        val sizePx = max(96, (52f * density).roundToInt())
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val radius = sizePx / 2f
        val inset = (sizePx * 0.02f).coerceAtLeast(1f)
        val drawRadius = radius - inset

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PHOTO_BACKGROUND_COLOR
        }
        canvas.drawCircle(radius, radius, drawRadius, backgroundPaint)

        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(
            sizePx / source.width.toFloat(),
            sizePx / source.height.toFloat()
        )
        val dx = (sizePx - source.width * scale) / 2f
        val dy = (sizePx - source.height * scale) / 2f
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
        )

        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            this.shader = shader
        }
        canvas.drawCircle(radius, radius, drawRadius, imagePaint)

        val borderWidth = (density * 1.5f).coerceAtLeast(1f)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PHOTO_BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        canvas.drawCircle(radius, radius, drawRadius - borderWidth / 2f, borderPaint)
        return output
    }

    private fun generateInitialAvatarBitmap(
        context: Context,
        sessionCode: String,
        contactName: String
    ): Bitmap? {
        val trimmed = contactName.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val initials = trimmed.split(" ")
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .mapNotNull { part -> part.firstOrNull()?.toString() }
            .map { char -> char.uppercase(Locale.getDefault()) }
            .take(2)
            .joinToString("")
            .ifEmpty { context.getString(R.string.contact_initial_placeholder) }

        val density = context.resources.displayMetrics.density
        val sizePx = max(96, (52f * density).roundToInt())
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colorIndex = stablePositiveHash(sessionCode) % AVATAR_COLORS.size
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AVATAR_COLORS[colorIndex]
        }
        val radius = sizePx / 2f
        val drawRadius = (radius - (sizePx * 0.02f)).coerceAtLeast(1f)
        canvas.drawCircle(radius, radius, drawRadius, backgroundPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.44f
        }
        val textBaseline = radius - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, radius, textBaseline, textPaint)
        return bitmap
    }

    private fun stablePositiveHash(value: String): Int {
        val raw = value.hashCode()
        if (raw == Int.MIN_VALUE) {
            return 0
        }
        return raw.absoluteValue
    }

    private const val PHOTO_BACKGROUND_COLOR = 0xFFE7EBF0.toInt()
    private const val PHOTO_BORDER_COLOR = 0xD9FFFFFF.toInt()
    private val AVATAR_COLORS = intArrayOf(
        0xFF006874.toInt(),
        0xFF3949AB.toInt(),
        0xFF00897B.toInt(),
        0xFFEF6C00.toInt(),
        0xFFD81B60.toInt(),
        0xFF5E35B1.toInt(),
        0xFF1B5E20.toInt(),
        0xFF546E7A.toInt()
    )
}
