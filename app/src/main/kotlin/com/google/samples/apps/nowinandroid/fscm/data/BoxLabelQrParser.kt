/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.fscm.data

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class BoxLabelQrItem(
    val skuCode: String,
    val quantity: Int,
)

@Serializable
data class BoxLabelQrPayload(
    val originalRawCode: String,
    val labelCode: String,
    val items: List<BoxLabelQrItem> = emptyList(),
    val format: BoxLabelQrFormat,
) {
    val totalQuantity: Int get() = items.sumOf(BoxLabelQrItem::quantity)
}

@Serializable
enum class BoxLabelQrFormat {
    Compact,
    ShortCode,
    LegacyV1,
    LegacyV2,
    LegacyV3,
}

class BoxLabelQrFormatException(message: String) : IllegalArgumentException(message)

fun isLikelyBoxLabelQr(rawCode: String): Boolean = runCatching { parseBoxLabelQr(rawCode) }.isSuccess

fun parseBoxLabelQr(rawCode: String): BoxLabelQrPayload {
    val raw = rawCode.trimScannerCharacters()
    if (raw.isBlank()) throw BoxLabelQrFormatException("箱唛二维码内容为空")

    return when {
        raw.startsWith(V1_PREFIX, ignoreCase = true) -> parseV1(raw)
        raw.startsWith("$V2_PREFIX|", ignoreCase = true) -> parseLegacyFields(raw, BoxLabelQrFormat.LegacyV2)
        raw.startsWith("$V3_PREFIX|", ignoreCase = true) -> parseLegacyFields(raw, BoxLabelQrFormat.LegacyV3)
        ',' in raw -> parseCompact(raw)
        raw.isBoxLabelShortCode() -> BoxLabelQrPayload(
            originalRawCode = raw,
            labelCode = raw.normalizedLabelCode(),
            format = BoxLabelQrFormat.ShortCode,
        )
        else -> throw BoxLabelQrFormatException("无法识别箱唛格式，请扫描完整箱唛二维码或输入唯一码")
    }
}

private fun parseCompact(raw: String): BoxLabelQrPayload {
    val parts = raw.split(',')
    val labelCode = parts.first().normalizedLabelCode()
    if (!labelCode.isBoxLabelShortCode()) throw BoxLabelQrFormatException("箱唛唯一码格式无效")
    if (parts.size == 1) throw BoxLabelQrFormatException("箱唛二维码缺少 SKU 和数量")
    val items = parts.drop(1).map(::parseSkuQuantity)
    validateTotalQuantity(items)
    return BoxLabelQrPayload(raw, labelCode, items, BoxLabelQrFormat.Compact)
}

private fun parseV1(raw: String): BoxLabelQrPayload {
    val boxUid = raw.substring(V1_PREFIX.length).trim()
    if (boxUid.isBlank()) throw BoxLabelQrFormatException("旧版箱唛二维码缺少箱级唯一标识")
    return BoxLabelQrPayload(raw, boxUid.toShortLabelCode(), format = BoxLabelQrFormat.LegacyV1)
}

private fun parseLegacyFields(raw: String, format: BoxLabelQrFormat): BoxLabelQrPayload {
    val fields = raw.split('|').drop(1).mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) null else part.substring(0, separator).trim().lowercase() to part.substring(separator + 1).decodePayloadValue()
    }.toMap()
    val labelCode = fields["c"]?.normalizedLabelCode()
        ?.takeIf(String::isBoxLabelShortCode)
        ?: fields["code"]?.normalizedLabelCode()?.takeIf(String::isBoxLabelShortCode)
        ?: fields["u"]?.takeIf(String::isNotBlank)?.toShortLabelCode()
        ?: fields["uid"]?.takeIf(String::isNotBlank)?.toShortLabelCode()
        ?: throw BoxLabelQrFormatException("旧版箱唛二维码缺少唯一码")
    val items = fields["s"].orEmpty().split(';').filter(String::isNotBlank).mapNotNull { value ->
        if ('=' in value) parseSkuQuantity(value) else null
    }
    if (items.isNotEmpty()) validateTotalQuantity(items)
    return BoxLabelQrPayload(raw, labelCode, items, format)
}

private fun parseSkuQuantity(value: String): BoxLabelQrItem {
    val separator = value.lastIndexOf('=')
    if (separator <= 0 || separator == value.lastIndex) {
        throw BoxLabelQrFormatException("SKU 数量格式无效：$value")
    }
    val skuCode = value.substring(0, separator).trim()
        .replace('，', ',')
        .replace('＝', '=')
    val quantity = value.substring(separator + 1).trim().toIntOrNull()
    if (skuCode.isBlank()) throw BoxLabelQrFormatException("箱唛二维码存在空 SKU")
    if (quantity == null || quantity <= 0) throw BoxLabelQrFormatException("SKU $skuCode 的数量必须为正整数")
    return BoxLabelQrItem(skuCode, quantity)
}

private fun validateTotalQuantity(items: List<BoxLabelQrItem>) {
    val total = items.sumOf { it.quantity.toLong() }
    if (total > Int.MAX_VALUE) throw BoxLabelQrFormatException("箱唛数量超出支持范围")
}

private fun String.trimScannerCharacters(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }

private fun String.normalizedLabelCode(): String = trim().uppercase(Locale.ROOT)

private fun String.isBoxLabelShortCode(): Boolean {
    val normalized = normalizedLabelCode()
    return normalized.length in 12..20 && normalized.length % 2 == 0 && normalized.all { it in '0'..'9' || it in 'A'..'F' }
}

private fun String.toShortLabelCode(): String = MessageDigest.getInstance("MD5")
    .digest(trim().toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02X".format(byte) }
    .take(12)

private fun String.decodePayloadValue(): String {
    val decoded = runCatching {
        URLDecoder.decode(trim(), StandardCharsets.UTF_8.name())
    }.getOrDefault(trim())
    return decoded
        .replace('／', '|')
        .replace('＝', '=')
        .replace('；', ';')
}

private const val V1_PREFIX = "FSCMBOX:v1:"
private const val V2_PREFIX = "FSCMBOX:v2"
private const val V3_PREFIX = "FSCMBOX:v3"
