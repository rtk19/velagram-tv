package com.velagram.tv;
import android.content.SharedPreferences;
import java.util.*;
/** Native tests must leave the owner's exact preferences (including encrypted values) intact. */
final class PreferenceSnapshot {
 final SharedPreferences prefs;
 final Map<String, ?> before;
 PreferenceSnapshot(Store store) { prefs = store.prefs; before = new HashMap<>(prefs.getAll()); }
 @SuppressWarnings("unchecked") void restore() {
   SharedPreferences.Editor e = prefs.edit();
   for (String key : prefs.getAll().keySet()) if (!before.containsKey(key)) e.remove(key);
   for (Map.Entry<String, ?> entry : before.entrySet()) {
     String k = entry.getKey(); Object v = entry.getValue();
     if (v instanceof String) e.putString(k, (String) v);
     else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
     else if (v instanceof Integer) e.putInt(k, (Integer) v);
     else if (v instanceof Long) e.putLong(k, (Long) v);
     else if (v instanceof Float) e.putFloat(k, (Float) v);
     else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
   }
   e.commit();
 }
}
