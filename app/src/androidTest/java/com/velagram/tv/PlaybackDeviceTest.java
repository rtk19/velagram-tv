package com.velagram.tv;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Physical decoder tests. Position/rate evidence does not certify subjective A/V sync. */
@androidx.media3.common.util.UnstableApi
public class PlaybackDeviceTest {
 Instrumentation i; Store store; PreferenceSnapshot snapshot; Activity a; File movie;
 @Before public void start() {
   i=InstrumentationRegistry.getInstrumentation(); store=new Store(i.getTargetContext()); snapshot=new PreferenceSnapshot(store);
   store.put("profile","Decoder QA "+UUID.randomUUID());
 }
 @After public void finish() {
   if(a!=null)i.runOnMainSync(()->a.finish());
   long end=android.os.SystemClock.elapsedRealtime()+10000;
   while(a!=null&&!a.isDestroyed()&&android.os.SystemClock.elapsedRealtime()<end)try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
   i.waitForIdleSync(); snapshot.restore(); if(movie!=null)movie.delete();
 }
 long read(java.util.function.LongSupplier value) { long[] r={0}; i.runOnMainSync(()->r[0]=value.getAsLong()); return r[0]; }
 void capture(String name) throws Exception {
   i.waitForIdleSync(); Thread.sleep(600);
   File dir=new File(i.getTargetContext().getExternalFilesDir(null),"tv-design-evidence");dir.mkdirs();
   try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))) {
     i.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
   }
 }
 @Test public void media3Mp4RatesAndRemote() throws Exception { verify(PlayerActivity.class,"mp4"); }
 @Test public void media3MkvRatesAndRemote() throws Exception { verify(PlayerActivity.class,"mkv"); }
 @Test public void vlcMkvRatesAndRemote() throws Exception { verify(VlcActivity.class,"mkv"); }
 void verify(Class<? extends Activity> type,String container) throws Exception {
   movie=new File(i.getTargetContext().getFilesDir(),"decoder-qa-"+UUID.randomUUID()+"."+container);
   try(InputStream in=i.getContext().getAssets().open("playback."+container);FileOutputStream out=new FileOutputStream(movie)) {
     byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
   }
   JSONObject media=Models.json("id","decoder-qa-"+UUID.randomUUID(),"url",Uri.fromFile(movie).toString(),"name","Big Buck Bunny · "+container.toUpperCase(Locale.ROOT));
   a=i.startActivitySync(new Intent(i.getTargetContext(),type).putExtra("media",media.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   CinemaControls c=type==PlayerActivity.class?((PlayerActivity)a).controls:((VlcActivity)a).controls;
   long end=android.os.SystemClock.elapsedRealtime()+20000;
   while(read(c.playback::duration)<10000&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(150);
   assertTrue("Video not decoded",read(c.playback::duration)>10000);
   String engine=type==PlayerActivity.class?"media3":"vlc";
   for(float rate:new float[]{1f,1.25f,1.5f,2f}) {
     i.runOnMainSync(()->{
       if(a instanceof PlayerActivity)((PlayerActivity)a).player.setPlaybackSpeed(rate);else((VlcActivity)a).player.setRate(rate);
       c.playback.seek(2000); if(!c.playback.playing())c.playback.toggle(); c.show();
     });
     Thread.sleep(900);long before=read(c.playback::position);Thread.sleep(1600);long advanced=read(c.playback::position)-before;
     assertTrue(engine+" "+container+" "+rate+"x position not advancing: "+advanced,advanced>1600*rate*0.6&&advanced<1600*rate*1.5+500);
     capture(engine+"-"+container+"-"+rate+"x");
   }
   i.runOnMainSync(()->{c.playback.seek(5000);c.hide();});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
   long pauseDeadline=android.os.SystemClock.elapsedRealtime()+2500;
   while(read(()->c.playback.playing()?1:0)!=0 && android.os.SystemClock.elapsedRealtime()<pauseDeadline)Thread.sleep(100);
   assertEquals(0,read(()->c.playback.playing()?1:0));assertEquals(1,read(()->c.visible()?1:0));
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);assertEquals(0,read(()->c.visible()?1:0));
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);assertEquals(1,read(()->c.visible()?1:0));
   capture(engine+"-"+container+"-paused");
   i.runOnMainSync(()->{c.playback.seek(5000);c.hide();});Thread.sleep(500);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);Thread.sleep(800);
   assertTrue("Hidden right did not seek",read(c.playback::position)>=14000);
   long p=read(c.playback::position);i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);Thread.sleep(300);
   assertTrue("Visible arrows unexpectedly seek",Math.abs(read(c.playback::position)-p)<1000);
   final View[] opener={null};i.runOnMainSync(()->opener[0]=a.getCurrentFocus());
   i.runOnMainSync(()->{c.playback.seek(2000);if(!c.playback.playing())c.playback.toggle();if(a instanceof PlayerActivity)((PlayerActivity)a).speed();else((VlcActivity)a).speed();});
   capture(engine+"-"+container+"-speed-panel");Thread.sleep(5000);
   assertEquals("Overlay hid during sheet",1,read(()->c.visible()?1:0));
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
   capture(engine+"-"+container+"-panel-return");
   i.runOnMainSync(()->assertSame("Panel did not restore its opener",opener[0],a.getCurrentFocus()));
   i.runOnMainSync(()->{if(a instanceof PlayerActivity)((PlayerActivity)a).subtitles();else((VlcActivity)a).subtitles();});
   capture(engine+"-"+container+"-subtitle-panel");
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
   i.runOnMainSync(() -> { c.playback.seek(3000); c.lockControls(); });
   Thread.sleep(400); long lockedPosition = read(c.playback::position);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(300);
   assertTrue("Locked remote unexpectedly sought", read(c.playback::position) - lockedPosition < 2000);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); assertEquals(1,read(()->c.visible()?1:0));
   i.runOnMainSync(() -> c.setSleepTimer(350)); Thread.sleep(1000);
   assertEquals("Sleep timer did not pause", 0, read(()->c.playback.playing()?1:0));
   capture(engine+"-"+container+"-sleep-ended");
 }
}
