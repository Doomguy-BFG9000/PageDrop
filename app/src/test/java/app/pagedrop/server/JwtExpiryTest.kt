package app.pagedrop.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtExpiryTest {

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun futureExpiry_isNotExpired() {
        assertFalse(isJwtExpired(jwt("""{"exp":2000}"""), nowSeconds = 1000))
    }

    @Test
    fun pastExpiry_isExpired() {
        assertTrue(isJwtExpired(jwt("""{"exp":1000}"""), nowSeconds = 2000))
    }

    @Test
    fun expiryWithinSkew_isExpired() {
        assertTrue(isJwtExpired(jwt("""{"exp":1030}"""), nowSeconds = 1000))
    }

    @Test
    fun malformedOrMissingExp_isExpired() {
        assertTrue(isJwtExpired("not-a-jwt"))
        assertTrue(isJwtExpired(jwt("""{"sub":"x"}""")))
    }
}
