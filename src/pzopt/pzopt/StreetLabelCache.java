package pzopt;

import java.util.ArrayList;
import zombie.worldMap.streets.CharLayout;

/** One map UI's last street-label layout and the view/street keys it describes. */
public final class StreetLabelCache {
   public final ArrayList<CharLayout> chars = new ArrayList<>();
   public Object streets;
   public long stamp;
   public long key;
   public boolean valid;
   public long pendingStamp;
   public long pendingKey;
}
