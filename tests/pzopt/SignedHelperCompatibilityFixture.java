package pzopt;

import zombie.characters.ecs.ECSComponent;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableType;

/** Jar-first CPU fixture for relocated caches/enum dispatch; never constructs a game entity. */
public final class SignedHelperCompatibilityFixture {
   private static final String INITIALIZED = "pzopt.test.componentHierarchyInitialized";

   private static class ComponentRoot extends ECSComponent {
      static { System.setProperty(INITIALIZED, "true"); }
   }

   private static final class ComponentLeaf extends ComponentRoot {
   }

   private static final class ComponentSibling extends ComponentRoot {
   }

   private static final class IndependentComponent extends ECSComponent {
   }

   private static void require(boolean value, String message) {
      if (!value) throw new AssertionError(message);
   }

   public static void main(String[] args) {
      System.clearProperty(INITIALIZED);
      require(ECSComponent.class.getSigners() != null, "fixture must define the stock component from its signed jar");
      @SuppressWarnings("unchecked")
      Class<? extends ECSComponent>[] classes = new Class[] {
         ECSComponent.class, ComponentRoot.class, ComponentLeaf.class, ComponentSibling.class, IndependentComponent.class
      };
      for (int pass = 0; pass < 1024; pass++) {
         for (Class<? extends ECSComponent> component : classes) {
            require(EcsClassCache.get(component) == ECSComponent.getECSClass(component),
               "cached key must match the native class walk: " + component.getName());
         }
      }
      require(System.getProperty(INITIALIZED) == null, "class-key lookup must not initialize component subclasses");
      try {
         EcsClassCache.get(null);
         throw new AssertionError("ClassValue retains its null-key exception; the caller supplies the null fallback");
      } catch (NullPointerException expected) {
      }

      for (AnimationVariableType type : AnimationVariableType.values()) {
         int expected = type == AnimationVariableType.Boolean ? 1 : type == AnimationVariableType.Int ? 2 : 0;
         require(AnimationVariableKinds.of(type) == expected, "typed variable dispatch: " + type);
      }
      try {
         AnimationVariableKinds.of(null);
         throw new AssertionError("enum dispatch must retain the original null exception");
      } catch (NullPointerException expected) {
      }
      System.out.println("SignedHelperCompatibilityFixture ok (native ECS parity, no entity construction, all variable kinds and null)");
   }
}
