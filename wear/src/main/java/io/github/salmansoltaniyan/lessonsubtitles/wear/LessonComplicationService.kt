package io.github.salmansoltaniyan.lessonsubtitles.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Lets the user put a shortcut to this app on the watch face (a "complication").
 * Tapping it opens the app. The content never changes, so it is never updated.
 */
class LessonComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, tapAction = true)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, tapAction = false)

    private fun build(type: ComplicationType, tapAction: Boolean): ComplicationData? {
        val description = PlainComplicationText.Builder(getString(R.string.app_name)).build()
        val icon = Icon.createWithResource(this, R.drawable.ic_complication)
        val monochrome = MonochromaticImage.Builder(icon).build()
        val open = if (tapAction) openApp() else null

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(getString(R.string.complication_text)).build(),
                description,
            ).setMonochromaticImage(monochrome).setTapAction(open).build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(monochrome, description).setTapAction(open).build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.ICON).build(),
                description,
            ).setTapAction(open).build()

            else -> null
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
