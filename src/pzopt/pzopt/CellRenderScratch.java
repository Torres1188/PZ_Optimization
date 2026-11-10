package pzopt;

import java.util.ArrayList;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoGridSquare;

/** Each existing tile-recording lane keeps its own lists and colours. */
public final class CellRenderScratch {
   public final ArrayList<IsoGridSquare> tempSquares = new ArrayList<>(64);
   public final ColorInfo light = new ColorInfo();
   public final ColorInfo floorCol = new ColorInfo();
}
