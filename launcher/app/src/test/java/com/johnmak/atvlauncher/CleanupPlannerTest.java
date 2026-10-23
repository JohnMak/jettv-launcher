package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CleanupPlannerTest {
    private static CleanupPlanner.PackageState st(boolean installed, boolean disabled, boolean updated, boolean persistent) {
        return new CleanupPlanner.PackageState(installed, disabled, true, updated, persistent);
    }

    private static CleanupPlanner.PackageState live() { return st(true, false, false, false); }

    private static CleanupProfile profile(String body) {
        return CleanupProfile.parse("t", "@match model=M\n" + body).value();
    }

    private static String render(List<CleanupPlanner.Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (CleanupPlanner.Step s : steps) sb.append(s.action.name().toLowerCase()).append(' ').append(s.pkg).append(';');
        return sb.toString();
    }

    @Test public void skipsDoneProtectedAbsentOptionalAndOrdersLaunchersLast() {
        CleanupProfile p = profile("disable? l.auncher requires=home\n"
                + "disable a.live\nremove b.gone\ndisable c.disabled\nremove d.notinstalled\nreset e.noupdates\nreset f.updated\n"
                + "remove com.android.providers.media\ndisable g.persistent\ndisable h.absent\n"
                + "remove? i.optional\n");
        Map<String, CleanupPlanner.PackageState> inv = new HashMap<>();
        inv.put("l.auncher", live());
        inv.put("a.live", live());
        inv.put("b.gone", live());
        inv.put("c.disabled", st(true, true, false, false));
        inv.put("d.notinstalled", st(false, false, false, false));
        inv.put("e.noupdates", live());
        inv.put("f.updated", st(true, false, true, false));
        inv.put("com.android.providers.media", live());
        inv.put("g.persistent", st(true, false, false, true));
        inv.put("i.optional", live());
        ProtectedPackages prot = ProtectedPackages.base();

        assertEquals("disable a.live;remove b.gone;reset f.updated;",
                render(CleanupPlanner.plan(p, inv, prot, false, false)));
        // optional entries only with includeOptional; requires=home only with HOME; launchers last
        assertEquals("disable a.live;remove b.gone;reset f.updated;remove i.optional;",
                render(CleanupPlanner.plan(p, inv, prot, false, true)));
        assertEquals("disable a.live;remove b.gone;reset f.updated;remove i.optional;disable l.auncher;",
                render(CleanupPlanner.plan(p, inv, prot, true, true)));
        assertEquals("disable a.live;remove b.gone;reset f.updated;",
                render(CleanupPlanner.plan(p, inv, prot, true, false)));
    }

    @Test public void dynamicProtectionBlocksEntries() {
        CleanupProfile p = profile("disable a.b\ndisable c.d\n");
        Map<String, CleanupPlanner.PackageState> inv = new HashMap<>();
        inv.put("a.b", live());
        inv.put("c.d", live());
        ProtectedPackages prot = ProtectedPackages.base().with(java.util.Collections.singleton("c.d"));
        assertEquals("disable a.b;", render(CleanupPlanner.plan(p, inv, prot, false, false)));
    }
}
