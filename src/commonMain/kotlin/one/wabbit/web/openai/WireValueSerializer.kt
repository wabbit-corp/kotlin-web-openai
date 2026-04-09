// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
internal open class PreservingWireValueSerializer<T>(
    serialName: String,
    knownValues: List<T>,
    private val wireName: (T) -> String,
    private val unknown: (String) -> T,
) : KSerializer<T?> {
    private val knownValuesByWireName: Map<String, T> = knownValues.associateBy(wireName)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(wireName(value))
        }
    }

    override fun deserialize(decoder: Decoder): T? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        val raw = decoder.decodeString()
        return knownValuesByWireName[raw] ?: unknown(raw)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal open class PreservingRequiredWireValueSerializer<T>(
    serialName: String,
    knownValues: List<T>,
    private val wireName: (T) -> String,
    private val unknown: (String) -> T,
) : KSerializer<T> {
    private val knownValuesByWireName: Map<String, T> = knownValues.associateBy(wireName)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        encoder.encodeString(wireName(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return knownValuesByWireName[raw] ?: unknown(raw)
    }
}
