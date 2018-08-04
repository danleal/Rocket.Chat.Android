package chat.rocket.android.emoji

import android.content.Context
import android.graphics.Bitmap
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import chat.rocket.android.emoji.internal.GlideApp
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.gif.GifDrawable
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async

class EmojiParser {

    companion object {

        private val regex = ":[\\w]+:".toRegex()

        /**
         * Parses a text string containing unicode characters and/or shortnames to a rendered
         * Spannable.
         *
         * @param text The text to parse
         * @param factory Optional. A [Spannable.Factory] instance to reuse when creating [Spannable].
         * @return A rendered Spannable containing any supported emoji.
         */
        fun parse(context: Context, text: CharSequence, factory: Spannable.Factory? = null): CharSequence {
            val unicodedText = EmojiRepository.shortnameToUnicode(text)
            val spannable = factory?.newSpannable(unicodedText)
                ?: SpannableString.valueOf(unicodedText)

            val typeface = EmojiRepository.cachedTypeface
            // Look for groups of emojis, set a EmojiTypefaceSpan with the emojione font.
            val length = spannable.length
            var inEmoji = false
            var emojiStart = 0
            var offset = 0
            while (offset < length) {
                val codepoint = unicodedText.codePointAt(offset)
                val count = Character.charCount(codepoint)
                // Skip control characters.
                if (codepoint == 0x2028) {
                    offset += count
                    continue
                }
                if (codepoint >= 0x200) {
                    if (!inEmoji) {
                        emojiStart = offset
                    }
                    inEmoji = true
                } else {
                    if (inEmoji) {
                        spannable.setSpan(EmojiTypefaceSpan("sans-serif", typeface),
                            emojiStart, offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    inEmoji = false
                }
                offset += count
                if (offset >= length && inEmoji) {
                    spannable.setSpan(EmojiTypefaceSpan("sans-serif", typeface),
                        emojiStart, offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val customEmojis = EmojiRepository.getCustomEmojis()

            val density = context.resources.displayMetrics.density
            val px = (22 * density).toInt()

            return spannable.also {
                regex.findAll(spannable).iterator().forEach { match ->
                    customEmojis.find { it.shortname.toLowerCase() == match.value.toLowerCase() }?.let {
                        it.url?.let { url ->
                            try {
                                val glideRequest = if (url.endsWith("gif", true)) {
                                    GlideApp.with(context).asGif()
                                } else {
                                    GlideApp.with(context).asBitmap()
                                }

                                val futureTarget = glideRequest
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .load(url)
                                    .submit(px, px)

                                val range = match.range
                                futureTarget.get()?.let { image ->
                                    if (image is Bitmap) {
                                        spannable.setSpan(ImageSpan(context, image), range.start,
                                            range.endInclusive + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    } else if (image is GifDrawable) {
                                        image.setBounds(0, 0, image.intrinsicWidth, image.intrinsicHeight)
                                        spannable.setSpan(ImageSpan(image), range.start,
                                            range.endInclusive + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    }
                                }
                            } catch (ex: Throwable) {
                                Log.e("EmojiParser", "", ex)
                            }
                        }
                    }
                }
            }
        }

        fun parseAsync(
            context: Context,
            text: CharSequence,
            factory: Spannable.Factory? = null
        ): Deferred<CharSequence> {
            return async(CommonPool) { parse(context, text, factory) }
        }
    }
}
