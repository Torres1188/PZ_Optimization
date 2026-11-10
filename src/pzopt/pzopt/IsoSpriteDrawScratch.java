package pzopt;

import zombie.core.textures.ColorInfo;
import zombie.iso.Vector3;

/** Per-recorder sprite scratch; the native owner supplies its private callback instance. */
public final class IsoSpriteDrawScratch {
   public final ColorInfo info = new ColorInfo();
   public final Vector3 colorInfoBackup = new Vector3();
   public final Vector3 spritePos = new Vector3();
   public final Object andThen;

   public IsoSpriteDrawScratch(Object andThen) {
      this.andThen = andThen;
   }
}
