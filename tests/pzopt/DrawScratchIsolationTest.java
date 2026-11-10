package pzopt;

import zombie.core.Color;
import zombie.core.textures.ColorInfo;

/** Signed-package relocation keeps game-thread aliases and independent recording-thread scratch. */
public final class DrawScratchIsolationTest {
   private static void require(boolean value, String message) {
      if (!value) throw new AssertionError(message);
   }

   public static void main(String[] args) {
      ColorInfo light = new ColorInfo();
      ColorInfo defaults = new ColorInfo(0.2F, 0.3F, 0.4F, 0.5F);
      Color[] colors = new Color[7];
      for (int i = 0; i < colors.length; i++) colors[i] = new Color(1, 1, 1, 1);
      GridWallScratch game = new GridWallScratch(light, defaults, colors[0], colors[1], colors[2],
         colors[3], colors[4], colors[5], colors[6]);
      require(game.lightInfoTemp == light && game.defColorInfo == defaults, "game colour aliases");
      require(game.tr == colors[0] && game.tl == colors[1] && game.br == colors[2] && game.bl == colors[3]
         && game.interp1 == colors[4] && game.interp2 == colors[5] && game.finalCol == colors[6], "game corner aliases");

      GridWallScratch first = new GridWallScratch(defaults);
      GridWallScratch second = new GridWallScratch(defaults);
      require(first.lightInfoTemp != light && first.lightInfoTemp != second.lightInfoTemp, "recorder light isolation");
      require(first.defColorInfo != defaults && first.defColorInfo != second.defColorInfo, "recorder default isolation");
      require(first.defColorInfo.r == 0.2F && first.defColorInfo.g == 0.3F
         && first.defColorInfo.b == 0.4F && first.defColorInfo.a == 0.5F, "recorder default copy");
      float originalRed = game.tr.r;
      first.tr.r = 0.2F;
      first.defColorInfo.a = 0.9F;
      require(second.tr.r == originalRed && game.tr.r == originalRed && defaults.a == 0.5F
         && second.defColorInfo.a == 0.5F, "recorder writes cannot alter game or peer scratch");

      Object callback = new Object();
      IsoSpriteDrawScratch spriteA = new IsoSpriteDrawScratch(callback);
      IsoSpriteDrawScratch spriteB = new IsoSpriteDrawScratch(new Object());
      require(spriteA.andThen == callback, "private native callback identity");
      require(spriteA.info != spriteB.info && spriteA.colorInfoBackup != spriteB.colorInfoBackup
         && spriteA.spritePos != spriteB.spritePos, "sprite scratch isolation");
      IsoObjectDrawScratch objectA = new IsoObjectDrawScratch();
      IsoObjectDrawScratch objectB = new IsoObjectDrawScratch();
      require(objectA.stCol != objectB.stCol && objectA.stCol2 != objectB.stCol2
         && objectA.modelCol != objectB.modelCol, "object scratch isolation");

      IndieGlTemps glA = new IndieGlTemps();
      IndieGlTemps glB = new IndieGlTemps();
      require(glA.tempInt != glB.tempInt && glA.temp2Ints != glB.temp2Ints && glA.temp3Ints != glB.temp3Ints
         && glA.temp4Ints != glB.temp4Ints && glA.temp4Booleans != glB.temp4Booleans
         && glA.tempIntFloat != glB.tempIntFloat && glA.shaderStack != glB.shaderStack, "GL scratch isolation");
      StreetLabelCache labelsA = new StreetLabelCache();
      StreetLabelCache labelsB = new StreetLabelCache();
      labelsA.chars.add(null);
      require(labelsB.chars.isEmpty() && !labelsA.valid && !labelsB.valid, "map layout cache isolation");
      System.out.println("DrawScratchIsolationTest ok");
   }
}
