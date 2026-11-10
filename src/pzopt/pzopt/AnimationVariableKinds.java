package pzopt;

import zombie.core.skinnedmodel.advancedanimation.AnimationVariableType;

/** actionConditionFast's existing enum table, outside the signed native condition package. */
public final class AnimationVariableKinds {
   private AnimationVariableKinds() {
   }

   public static int of(AnimationVariableType type) {
      return switch (type) {
         case Boolean -> 1;
         case Int -> 2;
         default -> 0;
      };
   }
}
