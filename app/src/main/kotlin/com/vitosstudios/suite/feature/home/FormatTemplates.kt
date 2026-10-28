package com.vitosstudios.suite.feature.home

import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.docx.DocxDocument
import com.vitosstudios.suite.docx.DocxTemplate

/** Templates provided by the format layers (all written by this app, no third-party files). */
internal object FormatTemplates {
    fun list(): List<NewFileTemplate> = buildList {
        for (t in DocxTemplate.entries) {
            add(NewFileTemplate("docx-${t.name}", t.title, "DOCX document", FileKind.DOCX, t.title) { f ->
                DocxDocument.createNew(t).use { it.save(f) }
            })
        }
        addAll(OtherFormatTemplates.list())
    }
}
