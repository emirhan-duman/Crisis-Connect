package com.auralis.crisisconnect.core.media

import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedMediaFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private fun store() = EncryptedMediaFiles(temporary.root) { key }
    private fun file(dir: String = "image_messages") = File(temporary.root, "$dir/media.jpg").apply { parentFile!!.mkdirs() }

    @Test fun everyPersistedMediaCategoryIsEncryptedAndRoundTrips() {
        for (dir in listOf("voice_messages", "image_messages", "image_thumbnails", "contact_avatars")) {
            val file = file(dir); val data = "private image or voice".toByteArray()
            store().write(file, data)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private image or voice"))
            assertArrayEquals(data, store().read(file))
        }
    }
    @Test fun legacyPlaintextMigratesWithoutChangingPathOrContents() {
        val file = file(); file.writeText("legacy-private")
        assertArrayEquals("legacy-private".toByteArray(), store().read(file))
        assertTrue(file.readText().startsWith("CCMEDIA1"))
        assertArrayEquals("legacy-private".toByteArray(), store().read(file))
    }
    @Test fun failedUpgradePreservesOriginalButDoesNotReturnUnprotectedData() {
        val file = file(); file.writeText("legacy-private")
        val unavailable = EncryptedMediaFiles(temporary.root) { throw IOException("locked") }
        assertThrows(IOException::class.java) { unavailable.read(file) }
        assertEquals("legacy-private", file.readText())
    }
    @Test fun tagTamperingAndUnavailableKeyFailClosedWithoutReplacingCiphertext() {
        val file = file(); store().write(file, "private".toByteArray())
        val sealed = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; file.writeBytes(sealed)
        assertThrows(IOException::class.java) { store().read(file) }
        assertArrayEquals(sealed, file.readBytes())
        val other = EncryptedMediaFiles(temporary.root) { SecretKeySpec(ByteArray(32), "AES") }
        assertThrows(IOException::class.java) { other.read(file) }
    }
    @Test fun successiveWritesUseDifferentNoncesAndReplaceAtomically() {
        val file = file(); val bytes = "private".toByteArray()
        store().write(file, bytes); val first = file.readBytes(); store().write(file, bytes)
        assertFalse(first.contentEquals(file.readBytes())); assertArrayEquals(bytes, store().read(file))
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }
    @Test fun avatarSessionMigrationAndUnmanagedCameraInputRemainReadable() {
        val source = file("contact_avatars"); store().write(source, "avatar".toByteArray())
        val target = File(source.parentFile, "new-session.jpg")
        store().write(target, store().read(source)); assertArrayEquals("avatar".toByteArray(), store().read(target))
        val camera = file("camera_images"); camera.writeText("capture")
        assertFalse(store().manages(camera)); assertEquals("capture", store().read(camera).toString(Charsets.UTF_8))
    }
    @Test fun canonicalAliasesStayEncryptedAndEscapingSymlinksCannotSelectPlaintext() {
        val file = file(); val alias = File(file.parentFile, "../image_messages/${file.name}")
        store().write(alias, "private".toByteArray())
        assertEquals("private", store().read(file).toString(Charsets.UTF_8))
        val outside = file("camera_images").apply { writeText("original") }
        val link = File(file.parentFile, "escape.jpg")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { store().write(link, "must-not-write-plaintext".toByteArray()) }
        assertEquals("original", outside.readText())
    }
}
