package pzopt;

import zombie.characters.ecs.ECSComponent;

/** ecsLookupFast's existing per-class memoisation, outside the signed game package. */
public final class EcsClassCache {
   private EcsClassCache() {
   }

   private static final ClassValue<Class<?>> CLASSES = new ClassValue<>() {
      @Override
      protected Class<?> computeValue(Class<?> clazz) {
         Class<?> found = null;
         for (Class<?> c = clazz; c != null && c != ECSComponent.class; c = c.getSuperclass()) {
            found = c;
         }
         return found;
      }
   };

   public static Class<?> get(Class<?> clazz) {
      return CLASSES.get(clazz);
   }
}
