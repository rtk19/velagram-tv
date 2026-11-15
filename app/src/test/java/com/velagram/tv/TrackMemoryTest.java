package com.velagram.tv;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
public class TrackMemoryTest {
 @Test public void languageIntentCrossesEngineLabelsAndTrackNumberChanges() {
   JSONObject preference=TrackMemory.choice("eng","English",0);
   assertTrue(TrackMemory.score(preference,"","Track 3 - [English]",0)>0);
   assertEquals(-1,TrackMemory.score(preference,"he","English commentary",0));
   assertEquals("he",TrackMemory.language("iw-IL",""));
   assertEquals("he",TrackMemory.language("","עברית"));
 }
 @Test public void commentaryAndAccessibilityLabelsRemainDistinct() {
   JSONObject preference=TrackMemory.choice("en","English commentary",0);
   assertTrue(TrackMemory.score(preference,"en","English commentary",0)>TrackMemory.score(preference,"en","English",0));
   assertEquals(-1,TrackMemory.score(TrackMemory.choice("","",0),"","Unidentified track",0));
 }
}
