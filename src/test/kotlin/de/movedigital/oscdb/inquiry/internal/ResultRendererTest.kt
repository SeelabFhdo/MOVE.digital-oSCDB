package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.OutputFormat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultRendererTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val renderer = ResultRenderer(json)

    private val meta = InquiryMeta(
        sql = "select …", previousSql = null, assumptions = listOf("feed 19"), feeds = listOf(19),
        rowCount = 2, truncated = false, model = "m", modelMillis = 10, queryMillis = 5, attempts = 1,
    )

    private val table = ResultTable(
        columns = listOf("line", "count(*)", "lat", "lon"),
        rows = listOf(listOf("U47", 12, 51.5, 7.4), listOf("say \"hi\", ok", null, null, null)),
        truncated = false,
    )

    @Test
    fun `json has meta and rows keyed by column`() {
        val tree = json.readTree(renderer.render(table, OutputFormat.JSON, meta))
        assertEquals(19, tree["meta"]["feeds"][0].asInt())
        assertEquals("U47", tree["rows"][0]["line"].asString())
        assertEquals(12, tree["rows"][0]["count(*)"].asInt())
        assertTrue(tree["rows"][1]["count(*)"].isNull)
    }

    @Test
    fun `csv quotes values with commas and quotes`() {
        val lines = renderer.render(table, OutputFormat.CSV, meta).lines()
        assertEquals("line,count(*),lat,lon", lines[0])
        assertEquals("U47,12,51.5,7.4", lines[1])
        assertEquals("\"say \"\"hi\"\", ok\",,,", lines[2])
    }

    @Test
    fun `xml is well-formed with sanitized element names`() {
        val xml = renderer.render(table, OutputFormat.XML, meta)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val row = doc.getElementsByTagName("row").item(0) as Element
        assertEquals("12", row.getElementsByTagName("count___").item(0).textContent)
        val nil = (doc.getElementsByTagName("row").item(1) as Element).getElementsByTagName("count___").item(0) as Element
        assertEquals("true", nil.getAttribute("nil"))
        assertEquals("feed 19", doc.getElementsByTagName("assumption").item(0).textContent)
    }

    @Test
    fun `xml element names never start with a digit`() {
        val xml = renderer.render(ResultTable(listOf("1st"), listOf(listOf("a")), false), OutputFormat.XML, meta)
        assertTrue("<_1st>a</_1st>" in xml, xml)
    }

    @Test
    fun `geojson points are lon-lat and rows without coordinates have no geometry`() {
        val tree = json.readTree(renderer.render(table, OutputFormat.GEOJSON, meta))
        assertEquals("FeatureCollection", tree["type"].asString())
        val first = tree["features"][0]
        assertEquals(7.4, first["geometry"]["coordinates"][0].asDouble())
        assertEquals(51.5, first["geometry"]["coordinates"][1].asDouble())
        assertEquals("U47", first["properties"]["line"].asString())
        assertFalse(first["properties"].has("lat"))
        assertTrue(tree["features"][1]["geometry"].isNull)
        assertEquals(19, tree["meta"]["feeds"][0].asInt())
    }

    @Test
    fun `coordinates are detected case-insensitively`() {
        assertTrue(renderer.hasCoordinates(ResultTable(listOf("LAT", "Lon"), emptyList(), false)))
        assertFalse(renderer.hasCoordinates(ResultTable(listOf("lat"), emptyList(), false)))
    }

    @Test
    fun `format names parse case-insensitively`() {
        assertEquals(OutputFormat.GEOJSON, OutputFormat.parse("GeoJSON"))
        assertNull(OutputFormat.parse("pdf"))
        assertNull(OutputFormat.parse(null))
    }
}
