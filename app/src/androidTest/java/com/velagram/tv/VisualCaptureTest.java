package com.velagram.tv;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
/** Short capture pass against real catalog and decoded video; no invented catalog/source results. */
@androidx.media3.common.util.UnstableApi
public class VisualCaptureTest {
 Instrumentation i; Store store; PreferenceSnapshot snapshot; Activity active; File movie;
 String stage;
 okhttp3.mockwebserver.MockWebServer server;
 @Before public void setup(){i=InstrumentationRegistry.getInstrumentation();store=new Store(i.getTargetContext());snapshot=new PreferenceSnapshot(store);stage=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("stage","after");}
 void close() throws Exception {
   if(active==null)return;Activity closing=active;i.runOnMainSync(closing::finish);
   long end=android.os.SystemClock.elapsedRealtime()+10000;while(!closing.isDestroyed()&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);
   i.waitForIdleSync();active=null;
 }
 @After public void cleanup() throws Exception {close();if(server!=null)server.shutdown();snapshot.restore();if(movie!=null)movie.delete();}
 void await(java.util.function.BooleanSupplier condition) throws Exception {
   long end=android.os.SystemClock.elapsedRealtime()+30000;while(android.os.SystemClock.elapsedRealtime()<end){boolean[] ok={false};i.runOnMainSync(()->ok[0]=condition.getAsBoolean());if(ok[0]){i.waitForIdleSync();return;}Thread.sleep(150);}fail("Capture surface did not load");
 }
 void capture(String name) throws Exception {i.waitForIdleSync();Thread.sleep(1000);File dir=new File(i.getTargetContext().getExternalFilesDir(null),"premium-visual/"+stage);dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){i.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
 View compose(ViewGroup root){for(int n=0;n<root.getChildCount();n++){View child=root.getChildAt(n);if(child instanceof androidx.compose.ui.platform.ComposeView)return child;if(child instanceof ViewGroup){View found=compose((ViewGroup)child);if(found!=null)return found;}}return null;}
 @Test public void captureFiveCoreScreens() throws Exception {
   active=i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity a=(MainActivity)active;
   await(()->a.content.findViewWithTag("artwork-shelf")!=null);capture("01-home");
   JSONObject title=a.api.details(Models.json("id",1399,"kind","tv"));
   i.runOnMainSync(()->a.detail(title));await(()->a.content.findViewWithTag("episode-section")!=null);capture("02-details");
   i.runOnMainSync(()->a.episodes(title,1));await(()->{View picker=a.content.findViewWithTag("episode-section");if(picker==null)return false;ViewGroup parent=(ViewGroup)picker.getParent();if(parent.getTag()==null&&parent.getParent() instanceof ViewGroup)parent=(ViewGroup)parent.getParent();return parent.getChildCount()>1&&compose(parent)!=null;});
   i.runOnMainSync(()->{View section=a.content.findViewWithTag("episode-section");if(section!=null&&section.getParent() instanceof ViewGroup){ViewGroup rows=(ViewGroup)section.getParent();if(rows.getParent() instanceof ViewGroup&&rows.getTag()==null)rows=(ViewGroup)rows.getParent();if(rows.getChildCount()>1)rows.getChildAt(1).requestFocus();}});capture("03-episodes");
   i.runOnMainSync(()->a.sources(title,1,1));await(()->!a.providerStatuses.isEmpty());Thread.sleep(2500);capture("04-sources");close();
   playerFrame();
 }
 @Test public void capturePlayerFrame() throws Exception { playerFrame(); }
 @Test public void supportedPlaylistSourceRow() throws Exception {
   store.put("profile","Source visual QA "+UUID.randomUUID());
   for(String provider:new String[]{"community","animeil","hianime","anikoto","telegram","addon"})store.put("source."+provider,"false");
   server=new okhttp3.mockwebserver.MockWebServer();
   File fixture=new File(i.getTargetContext().getExternalFilesDir(null),"premium-visual-fixture.mp4");
   byte[] bytes;
   try(InputStream in=fixture.isFile()?new FileInputStream(fixture):i.getContext().getAssets().open("playback.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bytes=out.toByteArray();}
   server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","video/mp4").setBody(new okio.Buffer().write(bytes)));server.start();
   store.mergeChannels(Models.m3u("#EXTM3U\n#EXTINF:-1 group-title=\"Movies\",Big Buck Bunny 2008 "+(fixture.isFile()?"720p":"360p")+" MP4 excerpt\n"+server.url("/movie/big-buck-bunny.mp4")+"\n",server.url("/playlist.m3u").toString()));
   active=i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity a=(MainActivity)active;
   List<JSONObject> results=a.api.catalog("search/movie?query=Big%20Buck%20Bunny","movie");JSONObject title=null;
   for(JSONObject result:results)if(Models.year(result).equals("2008")){title=a.api.details(result);break;}
   assertNotNull("Actual TMDB title unavailable",title);JSONObject catalog=title;
   i.runOnMainSync(()->a.sources(catalog,0,0));
   await(()->a.providerRows.get("IPTV playlists")!=null&&a.providerRows.get("IPTV playlists").getChildCount()>1);
   i.runOnMainSync(()->{ViewGroup group=a.providerRows.get("IPTV playlists");group.getChildAt(group.getChildCount()-1).requestFocus();});
   capture("04-sources-local-playlist");
 }
 @Test public void captureThemeFocus() throws Exception {
   active=i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity a=(MainActivity)active;
   for(String theme:TvUi.THEMES){
     store.put("theme",theme);i.runOnMainSync(()->a.home("Home"));
     await(()->a.content.findViewWithTag("artwork-shelf")!=null);
     i.runOnMainSync(()->a.content.getChildAt(0).requestFocus());capture("theme-"+theme.toLowerCase(Locale.ROOT));
   }
 }
 @Test public void capturePlayerPanels() throws Exception {
   playerFrame(); PlayerActivity p=(PlayerActivity)active;
   i.runOnMainSync(p::speed);capture("05-player-speed");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
   i.runOnMainSync(p::subtitles);capture("05-player-subtitles");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
   i.runOnMainSync(p::options);capture("05-player-more");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
 }
 void playerFrame() throws Exception {
   store.put("profile","Visual QA "+UUID.randomUUID());
   movie=new File(i.getTargetContext().getFilesDir(),"visual-qa.mp4");try(InputStream in=new File(i.getTargetContext().getExternalFilesDir(null),"premium-visual-fixture.mp4").isFile()
       ? new FileInputStream(new File(i.getTargetContext().getExternalFilesDir(null),"premium-visual-fixture.mp4")) : i.getContext().getAssets().open("playback.mp4");FileOutputStream out=new FileOutputStream(movie)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
   JSONObject media=Models.json("id","visual-qa","url",Uri.fromFile(movie).toString(),"name","Big Buck Bunny");
   active=i.startActivitySync(new Intent(i.getTargetContext(),PlayerActivity.class).putExtra("media",media.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));PlayerActivity p=(PlayerActivity)active;
   await(()->p.player!=null&&p.player.getDuration()>10000);i.runOnMainSync(()->{p.player.pause();p.player.seekTo(10000);p.controls.show();});await(()->p.player.getPlaybackState()==androidx.media3.common.Player.STATE_READY);Thread.sleep(1200);capture("05-player");
 }
}
