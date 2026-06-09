package com.mobatrade.core.auth

import org.apache.commons.codec.binary.Base32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.time.Instant

object TotpGenerator {

    /**
     * Generates a 6-digit time-based one-time password (TOTP) for the given base32 secret.
     * Implements standard RFC 6238 spec.
     * 
     * @param base32Secret The Base32-encoded TOTP secret key provided by Angel One
     * @return 6-digit TOTP code as a string (e.g. "123456")
     */
    fun generateTOTP(base32Secret: String, currentTimeSeconds: Long = java.time.Instant.now().epochSecond): String {
        try {
            val normalizedSecret = base32Secret.replace(" ", "").uppercase()
            val base32 = Base32()
            val decodedKey = base32.decode(normalizedSecret)

            // Step 1: Calculate the time steps (30-second windows since Unix Epoch)
            val timeStep = currentTimeSeconds / 30

            // Step 2: Convert time step to 8-byte big-endian byte array
            val buffer = ByteBuffer.allocate(8)
            buffer.putLong(timeStep)
            val timeBytes = buffer.array()

            // Step 3: Compute HMAC-SHA1 of time steps using the secret key
            val keySpec = SecretKeySpec(decodedKey, "HmacSHA1")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(keySpec)
            val hmacResult = mac.doFinal(timeBytes)

            // Step 4: Dynamically truncate HMAC hash to get a 4-byte index-based value
            val offset = hmacResult[hmacResult.size - 1].toInt() and 0xf
            val binary = ((hmacResult[offset].toInt() and 0x7f) shl 24) or
                         ((hmacResult[offset + 1].toInt() and 0xff) shl 16) or
                         ((hmacResult[offset + 2].toInt() and 0xff) shl 8) or
                         (hmacResult[offset + 3].toInt() and 0xff)

            // Step 5: Convert to 6-digit number and pad with leading zeros if necessary
            val otp = binary % 1_000_000
            return String.format("%06d", otp)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Failed to generate TOTP code", e)
        }
    }
}
