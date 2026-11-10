package pzopt;

import zombie.core.textures.ColorInfo;

/** Per-recorder object colours; the game thread continues using native static scratch. */
public final class IsoObjectDrawScratch {
   public final ColorInfo stCol = new ColorInfo();
   public final ColorInfo stCol2 = new ColorInfo();
   public final ColorInfo modelCol = new ColorInfo();
}
