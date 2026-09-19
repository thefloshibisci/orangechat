package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Amap uses [] for missing text and an object for streetNumber. */
internal fun decodeAmapAddress(body: String): AmapService.AddressResult {
    fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)
        ?.contentOrNull?.takeIf { it.isNotBlank() }
    val response = Json.parseToJsonElement(body) as? JsonObject
        ?: return AmapService.AddressResult(false, error = "Invalid response")
    val regeo = response["regeocode"] as? JsonObject
    if (response.text("status") != "1" || regeo == null) {
        // Retain only an API code, never provider-supplied messages or request URLs.
        val code = response.text("infocode")?.takeIf { it.matches(Regex("[0-9]{5}")) }
        return AmapService.AddressResult(false, error = "API error ${code ?: "unknown"}")
    }
    val component = regeo["addressComponent"] as? JsonObject ?: JsonObject(emptyMap())
    val street = component["streetNumber"] as? JsonObject
    return AmapService.AddressResult(
        success = true,
        formattedAddress = regeo.text("formatted_address"),
        province = component.text("province"),
        city = component.text("city") ?: component.text("province"),
        district = component.text("district"),
        street = street?.text("street") ?: component.text("street"),
        streetNumber = street?.text("number"),
        neighborhood = (component["neighborhood"] as? JsonObject)?.text("name"),
        building = (component["building"] as? JsonObject)?.text("name"),
        adcode = component.text("adcode"),
        citycode = component.text("citycode"),
    )
}
