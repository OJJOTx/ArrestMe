package com.arrestme.benchmark.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlValidatorTest {

    @Test
    fun `missing or blank URL returns Invalid`() {
        val nullResult = BackendUrlValidator.validate(null)
        assertTrue(nullResult is UrlValidationResult.Invalid)
        assertEquals("URL cannot be empty.", (nullResult as UrlValidationResult.Invalid).reason)

        val emptyResult = BackendUrlValidator.validate("")
        assertTrue(emptyResult is UrlValidationResult.Invalid)

        val blankResult = BackendUrlValidator.validate("   ")
        assertTrue(blankResult is UrlValidationResult.Invalid)
    }

    @Test
    fun `malformed URLs are rejected`() {
        val noScheme = BackendUrlValidator.validate("api.example.com")
        assertTrue(noScheme is UrlValidationResult.Invalid)

        val invalidScheme = BackendUrlValidator.validate("ftp://api.example.com")
        assertTrue(invalidScheme is UrlValidationResult.Invalid)

        val badPort = BackendUrlValidator.validate("https://api.example.com:99999")
        assertTrue(badPort is UrlValidationResult.Invalid)

        val noHost = BackendUrlValidator.validate("https://")
        assertTrue(noHost is UrlValidationResult.Invalid)
    }

    @Test
    fun `remote production endpoints require HTTPS`() {
        // Plain HTTP on public domain must be rejected
        val remoteHttp = BackendUrlValidator.validate("http://api.arrestme.org")
        assertTrue(remoteHttp is UrlValidationResult.Invalid)
        val reason = (remoteHttp as UrlValidationResult.Invalid).reason
        assertTrue("Expected HTTPS requirement in reason: $reason", reason.contains("HTTPS is required"))

        // Remote public IP must require HTTPS
        val remoteIpHttp = BackendUrlValidator.validate("http://93.184.216.34:3000")
        assertTrue(remoteIpHttp is UrlValidationResult.Invalid)

        // HTTPS on public domain must succeed
        val remoteHttps = BackendUrlValidator.validate("https://api.arrestme.org")
        assertTrue(remoteHttps is UrlValidationResult.Valid)
        val valid = remoteHttps as UrlValidationResult.Valid
        assertEquals("https://api.arrestme.org", valid.normalizedUrl)
        assertFalse(valid.isLocalOrPrivate)
    }

    @Test
    fun `local and private network endpoints permit HTTP`() {
        // Localhost
        val localhost = BackendUrlValidator.validate("http://localhost:3000")
        assertTrue(localhost is UrlValidationResult.Valid)
        assertTrue((localhost as UrlValidationResult.Valid).isLocalOrPrivate)
        assertEquals("http://localhost:3000", localhost.normalizedUrl)

        // 127.0.0.1 Loopback
        val loopback = BackendUrlValidator.validate("http://127.0.0.1:8080")
        assertTrue(loopback is UrlValidationResult.Valid)
        assertTrue((loopback as UrlValidationResult.Valid).isLocalOrPrivate)

        // Android Emulator Loopback
        val emulator = BackendUrlValidator.validate("http://10.0.2.2:3000")
        assertTrue(emulator is UrlValidationResult.Valid)
        assertTrue((emulator as UrlValidationResult.Valid).isLocalOrPrivate)
        assertEquals("http://10.0.2.2:3000", emulator.normalizedUrl)

        // RFC1918 192.168.x.x LAN IP
        val lan192 = BackendUrlValidator.validate("http://192.168.1.100:3000")
        assertTrue(lan192 is UrlValidationResult.Valid)
        assertTrue((lan192 as UrlValidationResult.Valid).isLocalOrPrivate)

        // RFC1918 10.x.x.x LAN IP
        val lan10 = BackendUrlValidator.validate("http://10.0.0.5:8000")
        assertTrue(lan10 is UrlValidationResult.Valid)
        assertTrue((lan10 as UrlValidationResult.Valid).isLocalOrPrivate)

        // RFC1918 172.16-31.x.x LAN IP
        val lan172 = BackendUrlValidator.validate("http://172.20.10.4:3000")
        assertTrue(lan172 is UrlValidationResult.Valid)
        assertTrue((lan172 as UrlValidationResult.Valid).isLocalOrPrivate)

        // mDNS .local
        val mdns = BackendUrlValidator.validate("http://homelab.local:3000")
        assertTrue(mdns is UrlValidationResult.Valid)
        assertTrue((mdns as UrlValidationResult.Valid).isLocalOrPrivate)
    }

    @Test
    fun `URL normalization handles trailing slashes and case`() {
        val withTrailing = BackendUrlValidator.validate("HTTPS://API.EXAMPLE.COM/api/")
        assertTrue(withTrailing is UrlValidationResult.Valid)
        val valid = withTrailing as UrlValidationResult.Valid
        assertEquals("https://api.example.com/api", valid.normalizedUrl)

        val defaultHttpsPort = BackendUrlValidator.validate("https://api.example.com:443")
        assertTrue(defaultHttpsPort is UrlValidationResult.Valid)
        assertEquals("https://api.example.com", (defaultHttpsPort as UrlValidationResult.Valid).normalizedUrl)

        val defaultHttpPort = BackendUrlValidator.validate("http://localhost:80")
        assertTrue(defaultHttpPort is UrlValidationResult.Valid)
        assertEquals("http://localhost", (defaultHttpPort as UrlValidationResult.Valid).normalizedUrl)

        // Trailing /api/v1 or /api/v1/ is stripped so Retrofit routes never duplicate /api/v1/api/v1
        val hostWithApiV1 = BackendUrlValidator.validate("https://api.arrestme-cloud.example.com/api/v1/")
        assertTrue(hostWithApiV1 is UrlValidationResult.Valid)
        assertEquals(
            "https://api.arrestme-cloud.example.com",
            (hostWithApiV1 as UrlValidationResult.Valid).normalizedUrl
        )

        val subpathWithApiV1 = BackendUrlValidator.validate("https://example.com/arrestme/API/V1")
        assertTrue(subpathWithApiV1 is UrlValidationResult.Valid)
        assertEquals(
            "https://example.com/arrestme",
            (subpathWithApiV1 as UrlValidationResult.Valid).normalizedUrl
        )
    }
}
