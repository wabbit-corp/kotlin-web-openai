package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class ModelObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    val permission: JsonArray? = null,
    val root: String? = null,
    val parent: String? = null,
)

@Serializable
data class ModelPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ModelObject> = emptyList(),
)
