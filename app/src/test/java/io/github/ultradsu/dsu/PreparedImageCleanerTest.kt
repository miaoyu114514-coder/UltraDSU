package io.github.ultradsu.dsu

import io.github.ultradsu.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PreparedImageCleanerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): Triple<File, DsuSlot, File> {
        val root = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val group = "prepared_${UUID.randomUUID()}"
        val file = File(root,"$id/$group/${UUID.randomUUID()}.img.gz").apply { parentFile.mkdirs(); writeBytes(ByteArray(32)) }
        val slot = DsuSlot(id=id,slotName="alpha",displayName="Alpha",state=SlotState.INSTALLED,
            images=listOf(PartitionImage("system","$group/${file.name}",4096,"system.img")))
        return Triple(root,slot,file)
    }
    @Test fun deletesConfirmedPreparedGroupAndPackageButKeepsOtherFilesAndSource() {
        val (root,slot,file)=fixture()
        val zip=File(file.parentFile,"package.zip").apply{writeBytes(ByteArray(11))}
        val legacy=File(root,"${slot.id}.img.gz").apply{writeBytes(ByteArray(7))}
        val source=temporary.newFile("original.7z").apply{writeBytes(byteArrayOf(1,2,3))}
        val unrelated=File(root,"other-slot/backup.raw").apply{parentFile.mkdirs();writeBytes(byteArrayOf(4,5))}
        val activePreparation=File(root,"${slot.id}/update_${UUID.randomUUID()}/new.img.gz").apply{parentFile.mkdirs();writeBytes(byteArrayOf(6))}
        assertEquals(50L,PreparedImageCleaner(root).clean(slot.copy(sourceUri=source.toURI().toString()),true))
        assertFalse(file.exists());assertFalse(zip.exists());assertFalse(legacy.exists())
        assertArrayEquals(byteArrayOf(1,2,3),source.readBytes());assertTrue(unrelated.exists());assertTrue(activePreparation.exists())
        assertEquals(0L,PreparedImageCleaner(root).clean(slot,true))
    }
    @Test fun submittedFailedCancelledAndPendingRepairsKeepTheirPreparedImages() {
        val (root,slot,file)=fixture();val cleaner=PreparedImageCleaner(root)
        assertEquals(0L,cleaner.clean(slot,false))
        for(state in listOf(SlotState.DRAFT,SlotState.PREPARED,SlotState.SUBMITTED,SlotState.INCOMPLETE,SlotState.MISSING))
            assertEquals(0L,cleaner.clean(slot.copy(state=state),true))
        assertEquals(0L,cleaner.clean(slot.copy(pendingImages=slot.images),true))
        assertTrue(file.exists())
    }
    @Test fun malformedPathsAreRejectedBeforeAnyDeletion() {
        val (root,slot,file)=fixture()
        for(path in listOf("../outside.img.gz","/data/gsi/dsu/alpha/system.img","prepared_invalid/file.img.gz")) {
            try { PreparedImageCleaner(root).clean(slot.copy(images=slot.images+PartitionImage("product",path,4096,"product.img")),true);fail() }
            catch(expected:IllegalArgumentException) { }
            assertTrue(file.exists())
        }
    }
    @Test fun symlinksCannotEscapeThePreparedDirectory() {
        val (root,slot,file)=fixture()
        val outside=temporary.newFile("outside.img").apply{writeBytes(byteArrayOf(9))}
        val link=File(file.parentFile,"escape")
        try { Files.createSymbolicLink(link.toPath(),outside.toPath()) }
        catch(unavailable:Exception) { assumeNoException(unavailable) }
        try { PreparedImageCleaner(root).clean(slot,true);fail() }
        catch(expected:IllegalStateException) { }
        assertTrue(file.exists());assertArrayEquals(byteArrayOf(9),outside.readBytes())
    }
}
