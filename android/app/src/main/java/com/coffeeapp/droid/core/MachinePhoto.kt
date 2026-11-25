// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).

package com.coffeeapp.droid.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.coffeeapp.droid.core.log.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's own machine photo for the Micra page (2.15.0), in place of La
 * Marzocco's product image.
 *
 * Picked with the system photo picker, then copied into app-private storage
 * scaled down, so the page does not depend on a gallery item that can be
 * deleted or on a URI permission that lapses. It is not backed up: picking it
 * again after an uninstall is cheap. A new pick replaces the file at once;
 * which picture the page shows is a setting, saved with SAVE as usual.
 */
object MachinePhoto {
    private const val TAG = "MachinePhoto"
    private const val FILE_NAME = "machine_photo.jpg"

    /** Longest side of the stored copy. The page shows it at 140 dp. */
    private const val MAX_SIDE_PX = 800

    private val _version = MutableStateFlow(0)

    /** Bumped after every successful pick, so a shown photo reloads. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    /** Copies the picked image in, scaled down. False if it could not be read. */
    suspend fun store(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeScaled(context, uri)
            val target = file(context)
            // Written aside and renamed, so a failed write never leaves half a file.
            val temp = File(context.filesDir, "$FILE_NAME.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "rename failed" }
            }
            FileLog.i(TAG, "Machine photo stored: ${bitmap.width}x${bitmap.height}, ${target.length() / 1024} KB")
            _version.value++
            true
        } catch (e: Exception) {
            FileLog.w(TAG, "Machine photo could not be read: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** The stored copy, or null when there is none or it does not decode. */
    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.path)
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            // ImageDecoder also applies the EXIF rotation, so a portrait shot
            // stays upright.
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1.0, MAX_SIDE_PX.toDouble() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // API 26-27: sample down on decode, then scale the rest of the way.
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("not an image")
        val scale = minOf(1.0, MAX_SIDE_PX.toDouble() / maxOf(decoded.width, decoded.height))
        if (scale >= 1.0) return decoded
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
