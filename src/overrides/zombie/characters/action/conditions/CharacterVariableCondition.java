package zombie.characters.action.conditions;

import org.w3c.dom.Element;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionState;
import zombie.characters.action.IActionCondition;
import zombie.characters.action.IActionCondition.IFactory;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandle; // pzopt: the pooled handle of an operand
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableReference;
import zombie.core.skinnedmodel.advancedanimation.IAnimatable;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSource;
import zombie.util.StringUtils;

public final class CharacterVariableCondition implements IActionCondition {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.action.conditions.CharacterVariableCondition");
   }

   private CharacterVariableCondition.Operator op;
   private Object lhsValue;
   private Object rhsValue;

   private static Object parseValue(String value, boolean parseForCharacterVariableLookup) {
      if (value.length() <= 0) {
         return value;
      }

      char first = value.charAt(0);
      if (first == '-' || first == '+' || first >= '0' && first <= '9') {
         int intVal = 0;
         if (first >= '0' && first <= '9') {
            intVal = first - '0';
         }

         int readPos;
         for (readPos = 1; readPos < value.length(); readPos++) {
            char chr = value.charAt(readPos);
            if (chr >= '0' && chr <= '9') {
               intVal = intVal * 10 + chr - 48;
            } else if (chr != ',') {
               if (chr != '.') {
                  return value;
               }

               readPos++;
               break;
            }
         }

         if (readPos == value.length()) {
            return intVal;
         }

         float floatVal = intVal;
         float divisor = 10.0F;

         while (readPos < value.length()) {
            char chr = value.charAt(readPos);
            if (chr >= '0' && chr <= '9') {
               floatVal += (chr - '0') / divisor;
               divisor *= 10.0F;
            } else if (chr != ',') {
               return value;
            }

            readPos++;
         }

         if (first == '-') {
            floatVal *= -1.0F;
         }

         return floatVal;
      } else {
         if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")) {
            return true;
         }

         if (!value.equalsIgnoreCase("false") && !value.equalsIgnoreCase("no")) {
            if (parseForCharacterVariableLookup) {
               if (first != '\'' && first != '"') {
                  return new CharacterVariableCondition.CharacterVariableLookup(value);
               }

               StringBuilder sb = new StringBuilder(value.length() - 2);

               for (int readPos = 1; readPos < value.length(); readPos++) {
                  char c = value.charAt(readPos);
                  switch (c) {
                     case '"':
                     case '\'':
                        if (c == first) {
                           return sb.toString();
                        }
                     default:
                        sb.append(c);
                        break;
                     case '\\':
                        sb.append(value.charAt(readPos));
                  }
               }

               return sb.toString();
            } else {
               return value;
            }
         } else {
            return false;
         }
      }
   }

   private boolean load(Element node) {
      switch (node.getNodeName()) {
         case "isTrue":
            this.op = CharacterVariableCondition.Operator.Equal;
            this.lhsValue = new CharacterVariableCondition.CharacterVariableLookup(node.getTextContent().trim());
            this.rhsValue = true;
            return true;
         case "isFalse":
            this.op = CharacterVariableCondition.Operator.Equal;
            this.lhsValue = new CharacterVariableCondition.CharacterVariableLookup(node.getTextContent().trim());
            this.rhsValue = false;
            return true;
         case "compare":
            switch (node.getAttribute("op").trim()) {
               case "=":
               case "==":
                  this.op = CharacterVariableCondition.Operator.Equal;
                  break;
               case "!=":
               case "<>":
                  this.op = CharacterVariableCondition.Operator.NotEqual;
                  break;
               case "<":
                  this.op = CharacterVariableCondition.Operator.Less;
                  break;
               case ">":
                  this.op = CharacterVariableCondition.Operator.Greater;
                  break;
               case "<=":
                  this.op = CharacterVariableCondition.Operator.LessEqual;
                  break;
               case ">=":
                  this.op = CharacterVariableCondition.Operator.GreaterEqual;
                  break;
               default:
                  return false;
            }

            this.loadCompareValues(node);
            return true;
         case "gtr":
            this.op = CharacterVariableCondition.Operator.Greater;
            this.loadCompareValues(node);
            return true;
         case "less":
            this.op = CharacterVariableCondition.Operator.Less;
            this.loadCompareValues(node);
            return true;
         case "equals":
            this.op = CharacterVariableCondition.Operator.Equal;
            this.loadCompareValues(node);
            return true;
         case "notEquals":
            this.op = CharacterVariableCondition.Operator.NotEqual;
            this.loadCompareValues(node);
            return true;
         case "lessEqual":
            this.op = CharacterVariableCondition.Operator.LessEqual;
            this.loadCompareValues(node);
            return true;
         case "gtrEqual":
            this.op = CharacterVariableCondition.Operator.GreaterEqual;
            this.loadCompareValues(node);
            return true;
         default:
            return false;
      }
   }

   private void loadCompareValues(Element node) {
      String lhsString = node.getAttribute("a").trim();
      String rhsString = node.getAttribute("b").trim();
      this.lhsValue = parseValue(lhsString, true);
      this.rhsValue = parseValue(rhsString, false);
   }

   private static Object resolveValue(Object value, IAnimationVariableSource owner) {
      if (value instanceof CharacterVariableCondition.CharacterVariableLookup lookUp) {
         if (pzopt.Overrides.enabled() && pzopt.Config.ACTION_CONDITION_FAST) {
            return lookUp.pzoptGetValue(owner); // pzopt: actionConditionFast, typed read for boolean / int variables
         }

         String variableValue = lookUp.getValueString(owner);
         return variableValue != null ? parseValue(variableValue, false) : null;
      } else {
         return value;
      }
   }

   private boolean resolveCompareTo(int result) {
      switch (this.op) {
         case Equal:
            return result == 0;
         case NotEqual:
            return result != 0;
         case Less:
            return result < 0;
         case Greater:
            return result > 0;
         case LessEqual:
            return result <= 0;
         case GreaterEqual:
            return result >= 0;
         default:
            return false;
      }
   }

   public boolean passes(ActionContext context, ActionState state) {
      IAnimatable owner = context.getOwner();
      Object lhsResolved = resolveValue(this.lhsValue, owner);
      Object rhsResolved = resolveValue(this.rhsValue, owner);
      if (lhsResolved == null && rhsResolved instanceof String string && StringUtils.isNullOrEmpty(string)) {
         if (this.op == CharacterVariableCondition.Operator.Equal) {
            return true;
         }

         if (this.op == CharacterVariableCondition.Operator.NotEqual) {
            return false;
         }

         boolean lhsIsFloat = true;
      }

      if (lhsResolved != null && rhsResolved != null) {
         if (lhsResolved.getClass().equals(rhsResolved.getClass())) {
            if (lhsResolved instanceof String s) {
               return this.resolveCompareTo(s.compareTo((String)rhsResolved));
            }

            if (lhsResolved instanceof Integer i) {
               return this.resolveCompareTo(i.compareTo((Integer)rhsResolved));
            }

            if (lhsResolved instanceof Float f) {
               return this.resolveCompareTo(f.compareTo((Float)rhsResolved));
            }

            if (lhsResolved instanceof Boolean b) {
               return this.resolveCompareTo(b.compareTo((Boolean)rhsResolved));
            }
         }

         boolean lhsIsInt = lhsResolved instanceof Integer;
         boolean lhsIsFloat = lhsResolved instanceof Float;
         boolean rhsIsInt = rhsResolved instanceof Integer;
         boolean rhsIsFloat = rhsResolved instanceof Float;
         if ((lhsIsInt || lhsIsFloat) && (rhsIsInt || rhsIsFloat)) {
            boolean lhsWasLookup = this.lhsValue instanceof CharacterVariableCondition.CharacterVariableLookup;
            boolean rhsWasLookup = this.rhsValue instanceof CharacterVariableCondition.CharacterVariableLookup;
            if (lhsWasLookup == rhsWasLookup) {
               float lhsFloat = lhsIsFloat ? (Float)lhsResolved : ((Integer)lhsResolved).intValue();
               float rhsFloat = rhsIsFloat ? (Float)rhsResolved : ((Integer)rhsResolved).intValue();
               return this.resolveCompareTo(Float.compare(lhsFloat, rhsFloat));
            }

            if (lhsWasLookup) {
               if (rhsIsFloat) {
                  float lhsFloat = lhsIsFloat ? (Float)lhsResolved : ((Integer)lhsResolved).intValue();
                  float rhsFloat = (Float)rhsResolved;
                  return this.resolveCompareTo(Float.compare(lhsFloat, rhsFloat));
               } else {
                  int lhsInt = lhsIsFloat ? (int)((Float)lhsResolved).floatValue() : (Integer)lhsResolved;
                  int rhsInt = (Integer)rhsResolved;
                  return this.resolveCompareTo(Integer.compare(lhsInt, rhsInt));
               }
            } else if (lhsIsFloat) {
               float lhsFloat = (Float)lhsResolved;
               float rhsFloat = rhsIsFloat ? (Float)rhsResolved : ((Integer)rhsResolved).intValue();
               return this.resolveCompareTo(Float.compare(lhsFloat, rhsFloat));
            } else {
               int lhsInt = (Integer)lhsResolved;
               int rhsInt = rhsIsFloat ? (int)((Float)rhsResolved).floatValue() : (Integer)rhsResolved;
               return this.resolveCompareTo(Integer.compare(lhsInt, rhsInt));
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public IActionCondition clone() {
      return this;
   }

   private static String getOpString(CharacterVariableCondition.Operator op) {
      switch (op) {
         case Equal:
            return " == ";
         case NotEqual:
            return " != ";
         case Less:
            return " < ";
         case Greater:
            return " > ";
         case LessEqual:
            return " <= ";
         case GreaterEqual:
            return " >=";
         default:
            return " ?? ";
      }
   }

   private static String valueToString(Object value) {
      return value instanceof String ? "\"" + value + "\"" : value.toString();
   }

   public String getDescription() {
      return valueToString(this.lhsValue) + getOpString(this.op) + valueToString(this.rhsValue);
   }

   @Override
   public String toString() {
      return this.toString("");
   }

   public String toString(String indent) {
      return indent + this.getClass().getName() + "{ " + this.getDescription() + " }";
   }

   // pzopt: actionEvalParallel. The variable lookups of this condition (null when the side is a constant), for the
   // snapshot the game thread takes before a zombie's transitions evaluate on a worker (pzopt.ActionEval).
   public Object pzoptLhsLookup() {
      return this.lhsValue instanceof CharacterVariableCondition.CharacterVariableLookup ? this.lhsValue : null;
   }

   public Object pzoptRhsLookup() {
      return this.rhsValue instanceof CharacterVariableCondition.CharacterVariableLookup ? this.rhsValue : null;
   }

   /**
    * pzopt: actionEvalParallel. For a lookup object of this condition: null when {@code owner} has no such variable or
    * it is a stored slot or an audited pure callback (safe to read on a worker); otherwise the value the worker must
    * see, read now on the game thread (a callback with side effects or shared scratch), {@code pzopt.ActionEval.NULL}
    * for a null value.
    */
   /**
    * pzopt: actionSnapshotFilter. Whether this operand can ever resolve to a callback with side effects, from its
    * variable name alone (fixed by the action XML) and the character's callback registry (fixed by its constructor):
    * a name the registry does not know can only ever be a stored slot, and a name in {@code PURE_CALLBACKS} is an
    * audited pure read, so neither needs the game thread. A sub-variable source (another character) stays conservative.
    * Computed once per state when the state's lookups are cached, so the per-frame snapshot walks only the few
    * operands that really need a read.
    */
   public static boolean pzoptNeedsSnapshot(Object lookup) {
      CharacterVariableCondition.CharacterVariableLookup lookUp = (CharacterVariableCondition.CharacterVariableLookup)lookup;
      if (lookUp.variableReference.getSubVariableSourceName() != null) {
         return true;
      }

      String name = lookUp.variableReference.getName();
      return name == null || name.isBlank() || pzopt.ActionEval.impureCallback(name);
   }

   /**
    * pzopt: whether two operands name the same variable of the same source, so one read serves both in a snapshot.
    * Operands come from the action XML, so the comparison is a one-off at cache time.
    */
   public static boolean pzoptSameVariable(Object a, Object b) {
      AnimationVariableReference ra = ((CharacterVariableCondition.CharacterVariableLookup)a).variableReference;
      AnimationVariableReference rb = ((CharacterVariableCondition.CharacterVariableLookup)b).variableReference;
      return StringUtils.equalsIgnoreCase(ra.getSubVariableSourceName(), rb.getSubVariableSourceName())
         && StringUtils.equalsIgnoreCase(ra.getName(), rb.getName());
   }

   public static Object pzoptSnapshotValue(Object lookup, IAnimationVariableSource owner) {
      return pzoptSnapshotValue(lookup, owner, false);
   }

   /**
    * pzopt: {@code directGameVariables} = the owner's action-state container holds no state variables at all, so no
    * state can shadow a game variable and the operand resolves straight to the character's own slot array. Stock's
    * path walks the container's current state and every sub-state first, per operand; the caller checks the container
    * once per snapshot instead.
    */
   public static Object pzoptSnapshotValue(Object lookup, IAnimationVariableSource owner, boolean directGameVariables) {
      CharacterVariableCondition.CharacterVariableLookup lookUp = (CharacterVariableCondition.CharacterVariableLookup)lookup;
      IAnimationVariableSlot slot = lookUp.pzoptResolve(owner, directGameVariables);
      if (slot == null || !(slot instanceof zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlotCallback)) {
         return null;
      }

      if (lookUp.pzoptPure == 0) {
         String key = slot.getKey();
         lookUp.pzoptPure = (byte)(key != null && pzopt.ActionEval.PURE_CALLBACKS.contains(key.toLowerCase(java.util.Locale.ENGLISH)) ? 1 : -1);
      }

      if (lookUp.pzoptPure > 0) {
         return null;
      }

      // pzopt: the slot is resolved, and this is the game thread building the snapshot, so read it straight —
      // no second resolution through the reference and no thread-local snapshot probe.
      Object value = pzoptReadSlot(slot);
      return value == null ? pzopt.ActionEval.NULL : value;
   }

   /** pzopt: the typed read of a resolved slot; the body pzoptGetValue uses once it has its slot. */
   private static Object pzoptReadSlot(IAnimationVariableSlot variableSlot) {
      switch (pzopt.AnimationVariableKinds.of(variableSlot.getType())) { // pzopt: same enum dispatch in a signer-safe mod helper
         case 1: // pzopt: Boolean
            return variableSlot.getValueBool() ? Boolean.TRUE : Boolean.FALSE;
         case 2: // pzopt: Int
            return Integer.valueOf(Math.abs(variableSlot.getValueInt()));
         default:
            String variableValue = variableSlot.getValueString();
            return variableValue != null ? parseValue(variableValue, false) : null;
      }
   }

   private static class CharacterVariableLookup {
      private final AnimationVariableReference variableReference;
      byte pzoptPure; // pzopt: actionEvalParallel, 0 unknown, 1 = pure callback (allowlist), -1 = snapshot before the batch
      private AnimationVariableHandle pzoptHandle; // pzopt: the reference's handle, allocated from the same pool, once
      private byte pzoptDirect; // pzopt: 0 unknown, 1 = no sub-variable source (handle path), -1 = use the reference

      /**
       * pzopt: the owner's slot for this operand. Stock re-derives it per call: a blank test that scans the name, the
       * handle from the reference (lazily allocated, then a field read) and the sub-source resolution. Without a
       * sub-source the whole chain is the pooled handle — the same object the reference would have cached — and the
       * owner's own lookup.
       */
      IAnimationVariableSlot pzoptResolve(IAnimationVariableSource owner) {
         return this.pzoptResolve(owner, false);
      }

      IAnimationVariableSlot pzoptResolve(IAnimationVariableSource owner, boolean directGameVariables) {
         if (this.pzoptDirect == 0) {
            String name = this.variableReference.getName();
            boolean direct = this.variableReference.getSubVariableSourceName() == null && name != null && !name.isBlank();
            this.pzoptDirect = (byte)(direct ? 1 : -1);
            if (direct) {
               this.pzoptHandle = AnimationVariableHandle.alloc(name);
            }
         }

         if (this.pzoptDirect <= 0) {
            return this.variableReference.getVariable(owner);
         }

         if (directGameVariables && owner instanceof zombie.core.skinnedmodel.advancedanimation.IAnimationVariableRegistry registry) {
            return registry.getGameVariablesInternal().getVariable(this.pzoptHandle);
         }

         return owner.getVariable(this.pzoptHandle);
      }

      public CharacterVariableLookup(String variableName) {
         this.variableReference = AnimationVariableReference.fromRawVariableName(variableName);
      }

      public String getValueString(IAnimationVariableSource owner) {
         IAnimationVariableSlot variableSlot = this.variableReference.getVariable(owner);
         return variableSlot == null ? null : variableSlot.getValueString();
      }

      // pzopt: actionConditionFast. Stock resolves every variable operand by printing the slot to a string and parsing the
      // string back into a boxed value, for every condition of every transition of every character every frame (the
      // transition evaluation was 7 % of the game thread on the Louisville horde, most of it here). A boolean slot prints
      // "true" / "false" and parses back to that boolean; an int slot prints the decimal and parses back to its absolute
      // value (the stock parser only applies the sign to decimals with a fraction), so both come straight from the typed
      // getter with the same result. Float and string slots keep the print-and-parse path: the parser's own digit
      // arithmetic is not Float.parseFloat, and a string may parse to a number or a boolean.
      public Object pzoptGetValue(IAnimationVariableSource owner) {
         java.util.IdentityHashMap<Object, Object> pzoptSnapshot = pzopt.ActionEval.currentSnapshot();
         if (pzoptSnapshot != null) {
            Object snap = pzoptSnapshot.get(this); // pzopt: actionEvalParallel, the value the game thread read before the batch
            if (snap != null) {
               return snap == pzopt.ActionEval.NULL ? null : snap;
            }
         }

         IAnimationVariableSlot variableSlot = this.pzoptResolve(owner);
         return variableSlot == null ? null : pzoptReadSlot(variableSlot);
      }

      @Override
      public String toString() {
         return this.variableReference.toString();
      }
   }

   public static class Factory implements IFactory {
      public IActionCondition create(Element conditionNode) {
         CharacterVariableCondition cond = new CharacterVariableCondition();
         return cond.load(conditionNode) ? cond : null;
      }
   }

   enum Operator {
      Equal,
      NotEqual,
      Less,
      Greater,
      LessEqual,
      GreaterEqual;
   }
}
