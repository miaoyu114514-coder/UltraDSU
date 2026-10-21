package io.github.ultradsu.dsu;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class RawImageExportTest {
    @Test public void copiesExactPartitionLengthAndChecksumsWithoutReadingPastEnd() throws Exception {
        byte[] source = new byte[8192]; java.util.Arrays.fill(source, (byte)7);
        ByteArrayInputStream input = new ByteArrayInputStream(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long[] progress = new long[2];
        String hash = RawImageExport.copy(input, output, 4096, () -> {}, (done,total) -> { progress[0]=done; progress[1]=total; });
        assertEquals(4096, output.size()); assertEquals(4096, input.available());
        assertArrayEquals(new long[]{4096,4096},progress);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray());
        StringBuilder expected = new StringBuilder(); for(byte b:digest) expected.append(String.format("%02x",b & 255));
        assertEquals(expected.toString(),hash);
    }
    @Test public void truncatedPartitionNeverProducesSuccess() throws Exception {
        try { RawImageExport.copy(new ByteArrayInputStream(new byte[4095]),new ByteArrayOutputStream(),4096,()->{},(a,b)->{}); fail(); }
        catch (EOFException expected) { }
    }
    @Test public void cancellationStopsBeforeTheNextWrite() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); int[] calls={0};
        try {
            RawImageExport.copy(new ByteArrayInputStream(new byte[2*1024*1024]),output,2*1024*1024,
                () -> { if (++calls[0] == 2) throw new CancellationException(); },(a,b)->{}); fail();
        } catch (CancellationException expected) { assertEquals(1024*1024,output.size()); }
    }
    @Test public void destinationFailureIsPropagated() throws Exception {
        try {
            RawImageExport.copy(new ByteArrayInputStream(new byte[4096]),new OutputStream(){ public void write(int value)throws IOException{throw new IOException("full");}},4096,()->{},(a,b)->{});
            fail();
        } catch(IOException expected) { assertEquals("full",expected.getMessage()); }
    }
}
