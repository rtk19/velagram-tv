package com.velagram.tv;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
public class CachePolicyTest {
 @Test public void onlyAppOwnedCacheIsTrimmedOldestFirst() throws Exception {
   File root=Files.createTempDirectory("velagram-cache-qa").toFile();
   File cache=new File(root,"cache"), saved=new File(root,"saved"), db=new File(root,"db");
   cache.mkdir(); saved.mkdir(); db.mkdir();
   File old=new File(cache,"old"), recent=new File(cache,"recent"), download=new File(saved,"offline"), login=new File(db,"session");
   for(File file:new File[]{old,recent,download,login})try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[16]);}
   old.setLastModified(1000);recent.setLastModified(System.currentTimeMillis());
   assertEquals(16,CachePolicy.trim(cache,16,0));assertFalse(old.exists());assertTrue(recent.exists());
   assertTrue(download.exists());assertTrue(login.exists());
   for(File file:new File[]{recent,download,login})file.delete();for(File dir:new File[]{cache,saved,db,root})dir.delete();
 }
}
