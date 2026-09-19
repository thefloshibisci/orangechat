package me.rerere.rikkahub.data.service

import org.junit.Assert.*
import org.junit.Test

class AmapAddressDecoderTest {
    @Test fun `object street number and empty city decode without losing address`() {
        val result = decodeAmapAddress("""{
            "status":"1","info":"OK","infocode":"10000",
            "regeocode":{"formatted_address":"Example address","addressComponent":{
                "province":"Example province","city":[],"district":"Example district",
                "citycode":[],"township":[],
                "streetNumber":{"street":"Example road","number":"42"},
                "neighborhood":{"name":[],"type":[]},"building":[]
            }}
        }""")
        assertTrue(result.success)
        assertEquals("Example address", result.formattedAddress)
        assertEquals("Example province", result.city)
        assertEquals("Example road", result.street)
        assertEquals("42", result.streetNumber)
        assertNull(result.neighborhood)
        assertNull(result.citycode)
    }

    @Test fun `empty optional components do not discard formatted address`() {
        val result = decodeAmapAddress("""{"status":"1","regeocode":{"formatted_address":"Known address","addressComponent":[]}}""")
        assertTrue(result.success)
        assertEquals("Known address", result.formattedAddress)
    }

    @Test fun `API failure does not expose upstream message`() {
        val result = decodeAmapAddress("""{"status":"0","info":"private key or URL","infocode":"10001"}""")
        assertFalse(result.success)
        assertEquals("API error 10001", result.error)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `invalid success payload is not an address`() {
        assertFalse(decodeAmapAddress("""{"status":"1","regeocode":[]}""").success)
    }
}
