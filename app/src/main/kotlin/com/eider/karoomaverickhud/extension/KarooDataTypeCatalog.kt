package com.eider.karoomaverickhud.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import io.hammerhead.karooext.models.DataType
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

/**
 * A data field provided by another installed Karoo extension (e.g. an MPA / time-to-exhaustion
 * power app, or a climbing app's time-to-summit). [id] is the full Karoo data-type id
 * ("TYPE_EXT::<extension>::<typeId>") to subscribe to; [label] is its human-readable name.
 */
data class DiscoveredField(
    val id: String,
    val label: String,
    val extensionId: String,
    val extensionName: String,
    val graphical: Boolean,
)

/**
 * Discovers the data types exposed by every installed Karoo extension, the same way the Karoo head
 * unit builds its field picker: it queries [PackageManager] for the extension services and parses
 * their `EXTENSION_INFO` meta-data XML. This lets the settings field picker offer extension-provided
 * fields (MPA, time to summit, …) that aren't in our own built-in catalog, with the built-ins kept
 * as defaults. The SDK has no runtime enumeration API, so this discovery is the only way in.
 */
object KarooDataTypeCatalog {

    private const val ACTION_KAROO_EXTENSION = "io.hammerhead.karooext.KAROO_EXTENSION"
    private const val META_EXTENSION_INFO = "io.hammerhead.karooext.EXTENSION_INFO"

    /** All data fields provided by other installed extensions (ours is excluded). */
    fun discover(context: Context): List<DiscoveredField> {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(ACTION_KAROO_EXTENSION), PackageManager.GET_META_DATA)
        }.getOrNull().orEmpty()

        val out = mutableListOf<DiscoveredField>()
        for (ri in services) {
            val si = ri.serviceInfo ?: continue
            if (si.packageName == context.packageName) continue // skip ourselves
            val metaRes = si.metaData?.getInt(META_EXTENSION_INFO, 0) ?: 0
            if (metaRes == 0) continue
            val res = runCatching { pm.getResourcesForApplication(si.applicationInfo) }.getOrNull() ?: continue
            val parser = runCatching { res.getXml(metaRes) }.getOrNull() ?: continue
            runCatching { parseExtension(parser, res, out) }
                .onFailure { Timber.w(it, "Failed to parse extension info for ${si.packageName}") }
            runCatching { parser.close() }
        }
        return out
    }

    /** Map of full data-type id → display label, for the renderer to unit-label generic fields. */
    fun labels(context: Context): Map<String, String> =
        discover(context).associate { it.id to it.label }

    /**
     * The data-type id of an installed MPA (Maximal Power Available, e.g. Xert) extension field,
     * matched by display label, or null when no such field is installed. Lets the on-climb overlay
     * surface MPA without the rider having to add it to a page. Prefers an exact "MPA" label, then
     * any field whose label mentions MPA / maximal power available.
     */
    fun mpaDataTypeId(context: Context): String? {
        val fields = discover(context)
        fields.firstOrNull { it.label.trim().equals("MPA", ignoreCase = true) }?.let { return it.id }
        return fields.firstOrNull {
            val l = it.label.lowercase()
            l.contains("mpa") || l.contains("maximal power") || l.contains("max power available")
        }?.id
    }

    private fun parseExtension(parser: XmlResourceParser, res: Resources, out: MutableList<DiscoveredField>) {
        var extId: String? = null
        var extName: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ExtensionInfo" -> {
                        extId = attr(parser, res, "id")
                        extName = attr(parser, res, "displayName") ?: extId
                    }
                    "DataType" -> {
                        val typeId = attr(parser, res, "typeId")
                        val display = attr(parser, res, "displayName")
                        val graphical = attr(parser, res, "graphical")?.toBoolean() ?: false
                        val ext = extId
                        if (ext != null && typeId != null) {
                            out.add(
                                DiscoveredField(
                                    id = DataType.dataTypeId(ext, typeId),
                                    label = display ?: typeId,
                                    extensionId = ext,
                                    extensionName = extName ?: ext,
                                    graphical = graphical,
                                ),
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Read a bare XML attribute by name, resolving "@string/…" references against the owning app's
     * [res] (so display names come back as real text, not resource ids).
     */
    private fun attr(parser: XmlResourceParser, res: Resources, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) {
                val resId = parser.getAttributeResourceValue(i, 0)
                return if (resId != 0) {
                    runCatching { res.getString(resId) }.getOrNull()
                } else {
                    parser.getAttributeValue(i)
                }
            }
        }
        return null
    }
}
