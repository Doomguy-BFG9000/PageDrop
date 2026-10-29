package app.pagedrop.data

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.converter.BookConverter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What PageDrop does by default with formats it can convert (EPUB, PDF, TXT). Kindle-ready formats are never touched. */
enum class SendFormatSetting {
    /** Convert to a Kindle book (MOBI), so the stock reader and KOReader can both open it. */
    CONVERT,

    /** Keep the original format: no conversion. */
    KEEP_ORIGINAL,
}

/** The persisted "Send format" choice. Defaults to [SendFormatSetting.CONVERT]. */
@Singleton
class SendFormatSettings internal constructor(private val prefs: SharedPreferences) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("pagedrop_send_format", Context.MODE_PRIVATE))

    private val _format = MutableStateFlow(read())
    val format: StateFlow<SendFormatSetting> = _format.asStateFlow()

    fun set(value: SendFormatSetting) {
        prefs.edit().putString(KEY, value.name).apply()
        _format.value = value
    }

    private fun read(): SendFormatSetting =
        SendFormatSetting.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: SendFormatSetting.CONVERT

    private companion object {
        const val KEY = "send_format"
    }
}

/** What to do with a file on its way to the Kindle. */
sealed interface SendPlan {
    /** No conversion, no upload: the file is used as is. */
    data object KeepOriginal : SendPlan

    /** Convert to MOBI with PageDrop's on-phone converter. */
    data object ConvertOnPhoneMobi : SendPlan

    /** Convert on the PageDrop server to [format] (lower-case). */
    data class Server(val format: String) : SendPlan
}

object SendFormatDecision {
    private val KINDLE_READY = setOf("MOBI", "AZW3", "AZW", "PRC")

    /** Whether PageDrop can convert [sourceFormat] at all: on this phone, or on the server when [serverAvailable]. */
    fun canConvert(sourceFormat: String, serverAvailable: Boolean = false): Boolean =
        sourceFormat.uppercase() !in KINDLE_READY && (BookConverter.canConvert(sourceFormat) || serverAvailable)

    /** Whether the user is offered a keep/convert choice for [sourceFormat]. */
    fun hasChoice(sourceFormat: String, convertOnly: Boolean = false, serverAvailable: Boolean = false): Boolean =
        !convertOnly && canConvert(sourceFormat, serverAvailable)

    /** The default for every format comes from the setting alone. */
    fun keepByDefault(setting: SendFormatSetting): Boolean = setting == SendFormatSetting.KEEP_ORIGINAL

    /**
     * @param keepOverride the user's per-book pick on the sheet (true = keep as is), or null for the [setting].
     * @param serverOutput the server output format when the conversion server is usable, else null.
     * @param convertOnly "Convert format" on a library book: always converts.
     */
    fun decide(
        sourceFormat: String,
        setting: SendFormatSetting,
        keepOverride: Boolean? = null,
        serverOutput: String? = null,
        convertOnly: Boolean = false,
    ): SendPlan {
        val keep = when {
            convertOnly -> false
            // Kindle-ready, or nothing to convert it with (no on-phone converter, server off): sent as is.
            !canConvert(sourceFormat, serverAvailable = !serverOutput.isNullOrBlank()) -> true
            else -> keepOverride ?: keepByDefault(setting)
        }
        if (keep) return SendPlan.KeepOriginal
        return serverOutput?.takeIf { it.isNotBlank() }?.let { SendPlan.Server(it.lowercase()) }
            ?: SendPlan.ConvertOnPhoneMobi
    }
}
