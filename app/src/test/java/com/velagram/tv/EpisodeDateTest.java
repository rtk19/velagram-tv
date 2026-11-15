package com.velagram.tv;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Locale;
public class EpisodeDateTest {
 @Test public void absentAndInvalidDatesAreOmitted() {
   for (String value : new String[] {null, "", "null", "2026-02-30", "2025-13-01", "26-1-1", "bad"})
     assertEquals("", EpisodeDate.format(value, Locale.US));
 }
 @Test public void validDateIsLocalizedWithoutTimezoneConversion() {
   assertEquals("Feb 29, 2024", EpisodeDate.format("2024-02-29", Locale.US));
   assertFalse(EpisodeDate.format("2024-02-29", Locale.forLanguageTag("he-IL")).isEmpty());
   assertNotEquals(EpisodeDate.format("2024-02-29", Locale.US), EpisodeDate.format("2024-02-29", Locale.GERMANY));
 }
}
