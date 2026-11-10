package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Pure production-plan bridge; never calls step/save or touches a live launcher. */
public final class ManagedAotPlanFixture {
    public static void main(String[] args) throws Exception {
        JSONObject original = new JSONObject(Files.readString(Path.of(args[0])));
        JSONObject planned = ManagedAotCache.plan(original, Path.of(args[1]), Path.of(args[2]),
                Boolean.parseBoolean(args[3]), true);
        Files.writeString(Path.of(args[4]), planned.toString(2) + "\n");
    }
}
