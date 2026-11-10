package pzopt;

/** luaIndexCache's existing per-key metatable-walk result and version stamps. */
public final class LuaChainEntry {
   public Object value;
   public long version;
   public long kver;
}
