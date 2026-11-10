package pzopt;

import java.util.Stack;
import zombie.ShaderStackEntry;
import zombie.core.opengl.GLState.C2IntsValue;
import zombie.core.opengl.GLState.C3IntsValue;
import zombie.core.opengl.GLState.C4BooleansValue;
import zombie.core.opengl.GLState.C4IntsValue;
import zombie.core.opengl.GLState.CIntFloatValue;
import zombie.core.opengl.GLState.CIntValue;

/** Per-recorder GL scratch, kept outside the signed native game packages. */
public final class IndieGlTemps {
   public final CIntValue tempInt = new CIntValue();
   public final C2IntsValue temp2Ints = new C2IntsValue();
   public final C3IntsValue temp3Ints = new C3IntsValue();
   public final C4IntsValue temp4Ints = new C4IntsValue();
   public final C4BooleansValue temp4Booleans = new C4BooleansValue();
   public final CIntFloatValue tempIntFloat = new CIntFloatValue();
   public final Stack<ShaderStackEntry> shaderStack = new Stack<>();
}
