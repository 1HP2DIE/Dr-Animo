@file:OptIn(ExperimentalSerializationApi::class)

package com.animo.app.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire format for `https://anikotoapi.site` — transcribed 1:1 from research/DATA_CONTRACT.md §4.
 *
 * Nothing here is guessed: every default below matches a measured absence pattern (absent key =
 * default, never null-in-JSON). `ignoreUnknownKeys` means the API may grow keys without breaking
 * the sync job.
 */
val AnimoJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/** Accepts a JSON string OR number for fields the API types inconsistently. */
object FlexibleStringSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class RecentAnimeResponse(
    val ok: Boolean = false,
    @SerialName("anikoto_domains") val domains: List<String> = emptyList(),
    val data: List<AnimeDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
data class PaginationDto(
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 100,
    val total: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
data class SeriesResponse(
    val ok: Boolean = false,
    val data: SeriesDataDto? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class SeriesDataDto(
    val anime: AnimeDto,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class AnimeDto(
    val id: Int,
    val title: String = "",
    val alternative: String = "",
    val titles: String = "",
    val native: String = "",
    val slug: String = "",
    val rating: String = "",
    val poster: String = "",
    @SerialName("is_dub") val isDub: Int? = null,
    @SerialName("is_sub") val isSub: Int? = null,
    val description: String = "",
    val aired: String = "",
    val season: String = "",
    val year: Int? = null,
    val duration: String = "",
    val status: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val score: String? = null,
    @SerialName("mal_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val malId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val episodes: String? = null,
    @SerialName("ani_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val aniId: String? = null,
    val source: String? = null,
    @SerialName("s_id") val sId: Long = 0L,
    @SerialName("background_image") val backgroundImage: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("next_air_schedule_time") val nextAirAt: Long? = null,
    @SerialName("next_air_ep") val nextAirEp: Int? = null,
    @SerialName("terms_by_type") val terms: TermsByTypeDto = TermsByTypeDto(),
)

@Serializable
data class TermsByTypeDto(
    val genre: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val type: List<String> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val title: String = "",
    @SerialName("jp_title") val jpTitle: String = "",
    val number: Int = 0,
    @SerialName("episode_embed_id") val embedId: String = "",
    @SerialName("embed_url") val embedUrl: EmbedUrlDto = EmbedUrlDto(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class EmbedUrlDto(
    val sub: String? = null,
    val dub: String? = null,
    val hsub: String? = null,
)
