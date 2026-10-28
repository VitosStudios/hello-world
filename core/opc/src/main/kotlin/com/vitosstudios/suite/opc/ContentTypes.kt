package com.vitosstudios.suite.opc

import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement

/** View over [Content_Types].xml. Only re-serialised when a Default or Override is changed. */
class ContentTypes internal constructor(private val part: Part) {
    private val root: XElement get() = part.xml().root

    private fun defaults() = root.elements().filter { it.localName == "Default" }
    private fun overrides() = root.elements().filter { it.localName == "Override" }

    fun defaultFor(extension: String): String? =
        defaults().firstOrNull { it.attr("Extension").equals(extension, ignoreCase = true) }?.attr("ContentType")

    fun overrideFor(partName: String): String? =
        overrides().firstOrNull { it.attr("PartName").equals(partName, ignoreCase = true) }?.attr("ContentType")

    fun contentTypeOf(partName: String): String? =
        overrideFor(partName) ?: defaultFor(PartNames.extension(partName))

    /** All parts whose content type is [type]. */
    fun partsOfType(pkg: OpcPackage, type: String): List<Part> = pkg.contentParts.filter { it.contentType == type }

    fun setOverride(partName: String, contentType: String) {
        val existing = overrides().firstOrNull { it.attr("PartName").equals(partName, ignoreCase = true) }
        if (existing != null) {
            existing.setAttr("ContentType", contentType)
            return
        }
        val e = root.createChild(Namespaces.CONTENT_TYPES, "Override")
        e.setAttr("PartName", partName)
        e.setAttr("ContentType", contentType)
        root.appendChild(e)
    }

    fun removeOverride(partName: String) {
        overrides().firstOrNull { it.attr("PartName").equals(partName, ignoreCase = true) }?.detach()
    }

    fun ensureDefault(extension: String, contentType: String) {
        if (defaultFor(extension) != null) return
        val e = root.createChild(Namespaces.CONTENT_TYPES, "Default")
        e.setAttr("Extension", extension)
        e.setAttr("ContentType", contentType)
        val lastDefault = defaults().lastOrNull()
        if (lastDefault != null) root.insertAfter(e, lastDefault) else root.insertChild(0, e)
    }
}
