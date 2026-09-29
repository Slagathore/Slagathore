package dev.slagathore.curbroute.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photo -> text lines, top-to-bottom, using ML Kit's on-device text recognizer.
 *
 * Why the rotation math: phone photos of a sheet are never perfectly level (your example is tilted a few
 * degrees). Sorting lines by their raw "top" pixel would then shuffle rows on the high side of the page.
 * Instead we measure the typical tilt of all lines and sort by position along the page's own "down"
 * direction.
 */
object SheetOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun readLines(context: Context, photo: Uri): List<String> {
        val image = InputImage.fromFilePath(context, photo) // also applies the photo's EXIF rotation
        val result: Text = recognizer.process(image).await()
        val lines = result.textBlocks.flatMap { it.lines }
        if (lines.isEmpty()) return emptyList()

        val tilt = lines.map { it.angle.toDouble() }.sorted()[lines.size / 2] // median angle, in degrees
        val rad = Math.toRadians(tilt)
        return lines.sortedBy { line ->
            val box = line.boundingBox ?: return@sortedBy 0.0
            // project the line's center onto the page's "down" direction
            -box.exactCenterX() * sin(rad) + box.exactCenterY() * cos(rad)
        }.map { it.text }
    }
}
