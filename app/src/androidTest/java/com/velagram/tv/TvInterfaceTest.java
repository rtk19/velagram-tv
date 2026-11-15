package com.velagram.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Actual physical-TV UI: network catalog, D-pad, retained navigation and real screen captures. */
@androidx.media3.common.util.UnstableApi
public class TvInterfaceTest {
 Instrumentation i;
 MainActivity a;
 PreferenceSnapshot snapshot;
 @Before public void start() {
   i = InstrumentationRegistry.getInstrumentation();
   Store store = new Store(i.getTargetContext()); snapshot = new PreferenceSnapshot(store);
   store.put("profile", "TV UI QA " + UUID.randomUUID());
   a = (MainActivity) i.startActivitySync(new Intent(i.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
 }
 @After public void finish() {
   if (a != null) i.runOnMainSync(() -> a.finish());
   i.waitForIdleSync(); snapshot.restore();
 }
 void await(java.util.function.BooleanSupplier test) throws Exception {
   long end = android.os.SystemClock.elapsedRealtime() + 25000;
   while (android.os.SystemClock.elapsedRealtime() < end) {
     boolean[] ok = new boolean[1]; i.runOnMainSync(() -> ok[0] = test.getAsBoolean());
     if (ok[0]) { i.waitForIdleSync(); return; } Thread.sleep(150);
   }
   fail("UI did not reach expected state");
 }
 void capture(String name) throws Exception {
   i.waitForIdleSync(); Thread.sleep(1200);
   File dir = new File(i.getTargetContext().getExternalFilesDir(null), "tv-design-evidence"); dir.mkdirs();
   try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
     i.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
   }
 }
 View compose(ViewGroup parent) {
   for (int n=0;n<parent.getChildCount();n++) {
     View child=parent.getChildAt(n);
     if (child instanceof androidx.compose.ui.platform.ComposeView) return child;
     if (child instanceof ViewGroup) { View result=compose((ViewGroup)child); if(result!=null)return result; }
   }
   return null;
 }
 @Test public void catalogEdgesDetailBackAndInlineEpisodes() throws Exception {
   await(() -> a.content.findViewWithTag("artwork-shelf") != null && a.content.getChildCount() >= 6);
   capture("home");
   i.runOnMainSync(() -> a.content.findViewWithTag("artwork-shelf").requestFocus());
   capture("poster-left");
   for (int n=0;n<19;n++) i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
   capture("poster-right");
   final View[] tree = new View[1], focused = new View[1]; final int[] offset = new int[1];
   i.runOnMainSync(() -> { tree[0]=a.root; focused[0]=a.getCurrentFocus(); offset[0]=a.scroll.getScrollY(); });
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
   await(() -> a.root != tree[0] && a.content.getChildCount() > 2);
   capture("detail");
   i.runOnMainSync(() -> a.onBackPressed());
   i.runOnMainSync(() -> { assertSame(tree[0], a.root); assertEquals(offset[0], a.scroll.getScrollY()); });
   capture("detail-back");
   JSONObject show=Models.json("id",1399,"kind","tv","name","Game of Thrones","seasons",new JSONArray().put(Models.json("season_number",1,"name","Season 1")).put(Models.json("season_number",2,"name","Season 2")));
   i.runOnMainSync(() -> a.episodes(show,1));
   await(() -> compose(a.content)!=null);
   i.runOnMainSync(() -> compose(a.content).requestFocus());
   capture("episode-dates");
   LinearLayout[] section=new LinearLayout[1];
   i.runOnMainSync(() -> section[0]=(LinearLayout)a.content.getChildAt(a.content.getChildCount()-1));
   i.runOnMainSync(() -> a.renderEpisodeSeason(section[0],show,2,true));
   await(() -> section[0].getChildCount()>2);
   capture("season-two");
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
   await(() -> !a.providerStatuses.isEmpty());
   capture("sources-loading"); Thread.sleep(5000); capture("sources-results");
   i.runOnMainSync(() -> a.onBackPressed()); capture("episode-back");
 }
 View focusedIsland() {
   View focus=a.getCurrentFocus();
   for(View node=focus;node!=null;node=node.getParent() instanceof View?(View)node.getParent():null)
     if(node instanceof androidx.compose.ui.platform.ComposeView)return node;
   return focus;
 }
 @Test public void remoteOnlyHeroShelfNavigationAndBack() throws Exception {
   await(()->a.content.findViewWithTag("artwork-shelf")!=null&&a.content.getChildCount()>=6);
   Thread.sleep(600);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.waitForIdleSync();Thread.sleep(350);
   View[] first={null};i.runOnMainSync(()->{first[0]=focusedIsland();assertEquals("Down scrolled without entering the shelf","artwork-shelf",first[0].getTag());});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
   capture("remote-only-first-shelf");
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.waitForIdleSync();
   i.runOnMainSync(()->assertNotSame("Down stayed in the first shelf",first[0],focusedIsland()));
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);i.waitForIdleSync();
   i.runOnMainSync(()->assertSame("Up lost the originating shelf",first[0],focusedIsland()));
   View[] tree={null};int[] offset={0};i.runOnMainSync(()->{tree[0]=a.root;offset[0]=a.scroll.getScrollY();});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);await(()->a.root!=tree[0]);Thread.sleep(800);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await(()->a.root==tree[0]);Thread.sleep(500);
   i.runOnMainSync(()->{assertSame(first[0],focusedIsland());assertEquals(offset[0],a.scroll.getScrollY());});
   capture("remote-only-back");
 }
 @Test public void rtlRemoteEntersContentFromRail() throws Exception {
   await(()->a.content.findViewWithTag("artwork-shelf")!=null);Thread.sleep(400);
   i.runOnMainSync(()->{a.root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);a.nav.getChildAt(1).requestFocus();});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);i.waitForIdleSync();
   i.runOnMainSync(()->assertTrue("RTL Left did not enter browse content",a.content.hasFocus()));
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.waitForIdleSync();
   i.runOnMainSync(()->assertEquals("artwork-shelf",focusedIsland().getTag()));
   capture("remote-only-rtl-shelf");
 }
 @Test public void remoteSeasonEpisodeAndSourceRoundtrip() throws Exception {
   JSONObject title=a.api.details(Models.json("id",1399,"kind","tv"));
   i.runOnMainSync(()->a.episodes(title,1));
   await(()->{View picker=a.content.findViewWithTag("episode-section");return picker!=null&&((ViewGroup)picker.getParent().getParent()).getChildCount()>2;});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(300);
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
   await(()->{View picker=a.content.findViewWithTag("episode-section");return picker!=null&&((ViewGroup)picker.getParent().getParent()).getChildCount()>2&&Integer.valueOf(2).equals(((View)picker.getParent().getParent()).getTag());});
   View[] origin={null};View[] tree={null};i.runOnMainSync(()->{origin[0]=focusedIsland();tree[0]=a.root;assertTrue(origin[0] instanceof androidx.compose.ui.platform.ComposeView);});
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);await(()->a.root!=tree[0]&&!a.providerStatuses.isEmpty());
   i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await(()->a.root==tree[0]);Thread.sleep(400);
   i.runOnMainSync(()->assertSame("Sources Back lost the episode",origin[0],focusedIsland()));
   capture("remote-only-episode-back");
 }
 @Test public void pendingNetworkResultsSurviveBack() throws Exception {
   final boolean[] arrived = {false};
   i.runOnMainSync(() -> {
     a.load(() -> { Thread.sleep(700); return "ready"; }, value -> { arrived[0] = true; a.note(value); });
     a.push(() -> { a.shell("More"); a.heading("Temporary route", ""); });
   });
   Thread.sleep(1000);
   i.runOnMainSync(() -> { assertFalse(arrived[0]); a.onBackPressed(); assertTrue(arrived[0]); });
 }
 @Test public void telegramConnectionAndStorageSurfaces() throws Exception {
   i.runOnMainSync(() -> a.home("Telegram"));
   await(() -> a.tg != null);
   Thread.sleep(3000);
   if (!a.tg.ready()) capture("telegram-unconnected");
   i.runOnMainSync(() -> a.storage());
   capture("storage");
 }
 @Test public void hebrewDatesAndMissingArtworkUseRealComponents() throws Exception {
   i.runOnMainSync(() -> {
     a.shell("Series");
     a.root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
     JSONObject show=Models.json("id",999999,"kind","tv","name","כותרת ארוכה מאוד לבדיקה של ניווט ושמות פרקים בעברית");
     JSONObject episode=Models.json("episode_number",1,"name","פרק ראשון עם שם ארוך מאוד לבדיקת חיתוך תקין", "air_date","2024-02-29","overview","תיאור הפרק בעברית — בדיקת תאריך, תמונה חסרה וניווט בשלט.","runtime",45);
     JSONObject request=a.episodeRequest(show,1,episode);
     a.store.progress(Store.progressKey(request),120000,900000,request);
     a.store.setWatched(request,true);
     a.content.addView(BrowseComponents.episode(a,episode,request,a.store,()->{},()->{}),new LinearLayout.LayoutParams(-1,a.dp(112)));
     episode=Models.json("episode_number",2,"name","Missing date and image","air_date","invalid","overview","No invented release date is shown.");
     a.content.addView(BrowseComponents.episode(a,episode,a.episodeRequest(show,1,episode),a.store,()->{},()->{}),new LinearLayout.LayoutParams(-1,a.dp(112)));
     compose(a.content).requestFocus();
   });
   capture("hebrew-and-empty-image");
 }
}
