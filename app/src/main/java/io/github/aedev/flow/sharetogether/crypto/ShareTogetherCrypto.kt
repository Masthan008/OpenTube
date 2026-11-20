package io.github.aedev.flow.sharetogether.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end cryptographic suite for Share Together rooms.
 *
 * Generates human-friendly room codes, computes topic tags for Nostr relays,
 * and seals/opens message payloads using AES-256-GCM.
 */
object ShareTogetherCrypto {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_BODY_LENGTH = 8
    private const val ITERATIONS = 20_000
    private val random = SecureRandom()

    /**
     * Generate an aesthetic room code, e.g. "OT-7492-9DTE".
     */
    fun generateRoomCode(): String {
        val part1 = String(CharArray(4) { ALPHABET[random.nextInt(ALPHABET.length)] })
        val part2 = String(CharArray(4) { ALPHABET[random.nextInt(ALPHABET.length)] })
        return "OT-$part1-$part2"
    }

    /**
     * Clean and normalize user entered room codes (strips spaces, dashes, links, casing).
     */
    fun normalizeCode(input: String): String {
        val trimmed = input.trim().uppercase()
        val withoutPrefix = trimmed.removePrefix("OT-").removePrefix("OT")
        val cleaned = withoutPrefix.filter { it.isLetterOrDigit() }
        return cleaned.ifBlank { trimmed }
    }

    /**
     * Compute the 32-character hex topic tag that Nostr relays index the room under.
     * The tag reveals zero information about the secret room code.
     */
    fun computeTopicTag(code: String): String {
        val normalized = normalizeCode(code)
        val digest = MessageDigest.getInstance("SHA-256").digest("opentube/together/tag/$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Derive a 256-bit AES secret key from the room code using PBKDF2.
     */
    fun deriveSecretKey(code: String): SecretKeySpec {
        val normalized = normalizeCode(code)
        val spec = PBEKeySpec(normalized.toCharArray(), "opentube/together/key".toByteArray(Charsets.UTF_8), ITERATIONS, 256)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(secret, "AES")
    }

    /**
     * Encrypt a string using AES-GCM with a fresh 12-byte IV and topic tag as authenticated data (AAD).
     */
    fun seal(
        key: SecretKeySpec,
        tag: String,
        plainText: String,
    ): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(tag.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return java.util.Base64
            .getEncoder()
            .encodeToString(iv + ciphertext)
    }

    /**
     * Decrypt an AES-GCM sealed string. Returns null if corrupted or wrong room code.
     */
    fun open(
        key: SecretKeySpec,
        tag: String,
        sealedText: String,
    ): String? =
        runCatching {
            val all =
                java.util.Base64
                    .getDecoder()
                    .decode(sealedText)
            if (all.size < 12 + 16) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
            cipher.updateAAD(tag.toByteArray(Charsets.UTF_8))
            val plainBytes = cipher.doFinal(all, 12, all.size - 12)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
}
