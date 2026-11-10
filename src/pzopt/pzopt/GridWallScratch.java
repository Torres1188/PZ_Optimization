package pzopt;

import zombie.core.Color;
import zombie.core.textures.ColorInfo;

/** Wall-drawing scratch: native static objects on the game thread, fresh objects per recorder. */
public final class GridWallScratch {
   public int colu, coll, colr, colu2, coll2, colr2;
   public boolean circleStencil, wallCutawayW, wallCutawayN;
   public final ColorInfo lightInfoTemp;
   public final ColorInfo defColorInfo;
   public final Color tr, tl, br, bl, interp1, interp2, finalCol;

   public GridWallScratch(ColorInfo defaultColor) {
      this(new ColorInfo(), new ColorInfo(), new Color(1, 1, 1, 1), new Color(1, 1, 1, 1),
         new Color(1, 1, 1, 1), new Color(1, 1, 1, 1), new Color(1, 1, 1, 1),
         new Color(1, 1, 1, 1), new Color(1, 1, 1, 1));
      this.defColorInfo.set(defaultColor);
   }

   public GridWallScratch(ColorInfo lightInfoTemp, ColorInfo defColorInfo, Color tr, Color tl,
         Color br, Color bl, Color interp1, Color interp2, Color finalCol) {
      this.lightInfoTemp = lightInfoTemp;
      this.defColorInfo = defColorInfo;
      this.tr = tr;
      this.tl = tl;
      this.br = br;
      this.bl = bl;
      this.interp1 = interp1;
      this.interp2 = interp2;
      this.finalCol = finalCol;
   }
}
