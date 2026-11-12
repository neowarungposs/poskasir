package com.neowarungposs.poskasir.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal .xlsx (Office Open XML) writer with no dependencies.
 * Cells: Number -> numeric cell, anything else -> inline string. First row of each sheet is bold.
 */
class XlsxWriter {
    private val sheets = mutableListOf<Pair<String, List<List<Any?>>>>()

    fun sheet(name: String, rows: List<List<Any?>>): XlsxWriter {
        val safe = name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31)
        sheets += safe to rows
        return this
    }

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(path: String, body: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach {
                    append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("</Types>")
            })
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, (name, _) ->
                    append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                append("</sheets></workbook>")
            })
            entry("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach {
                    append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf/><xf fontId="1" applyFont="1"/><xf numFmtId="164" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, (_, rows) ->
                entry("xl/worksheets/sheet${i + 1}.xml", buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                    rows.forEachIndexed { r, row ->
                        append("""<row r="${r + 1}">""")
                        row.forEachIndexed { c, v ->
                            val ref = col(c) + (r + 1)
                            when {
                                v == null -> {}
                                v is Number && r > 0 -> {
                                    val style = if (v is Long || v is Int) " s=\"2\"" else ""
                                    append("""<c r="$ref"$style><v>$v</v></c>""")
                                }
                                else -> {
                                    val style = if (r == 0) " s=\"1\"" else ""
                                    append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                                }
                            }
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                })
            }
        }
    }

    private fun col(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
