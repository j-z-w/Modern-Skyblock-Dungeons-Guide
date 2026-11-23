package kr.syeyoung.dungeonsguide.mod.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.CommunityRoute;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.OdinWaypointParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteAction;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RoutePack;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.RouteStep;
import kr.syeyoung.dungeonsguide.mod.dungeon.communityroute.SrmRouteParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Inline fixtures: format coverage + defensive parsing of malformed records. */
public class RouteParserTest {

    private static final String SRM = """
        {
          "#origin": "fowroutes.json by the community",
          "#copyright": "Wadey",
          "Version": 3,
          "Test-Room-2": [
            {
              "locations": [[5, 70, 5], [10, 71, 6]],
              "etherwarps": [[20, 72, 8]],
              "mines": [[15, 70, 10], [16, 70, 10]],
              "tnts": [[30, 69, 30]],
              "enderpearls": [[40.5, 80, 40.5]],
              "enderpearlangles": [[90.0, -35.5]],
              "interacts": [[12, 70, 12]],
              "secret": {"type": "item", "location": [25, 70, 25]}
            },
            {
              "locations": [[7, 70, 7]],
              "secret": {"type": "bat", "location": [9, 72, 9]}
            }
          ],
          "Test-Room-2:1": [
            {
              "locations": [[1, 70, 1]],
              "secret": {"type": "interact", "location": [2, 70, 2]}
            }
          ],
          "Broken-Room-1": [
            "not-an-object",
            {"locations": "oops"},
            null,
            {"etherwarps": [[3, 70, 3]]}
          ]
        }
        """;

    @Test
    void srmParsesActionsSecretsAndVariants() {
        RoutePack pack = new SrmRouteParser().parse("srm:test", "test.json", new StringReader(SRM));
        assertEquals(RoutePack.Format.SRM, pack.format);
        assertEquals(2, pack.roomCount());

        List<CommunityRoute> routes = pack.routesFor("Test-Room-2");
        assertEquals(2, routes.size());
        // variant 0 (base) then :1
        assertEquals(0, routes.get(0).variant);
        assertEquals(1, routes.get(1).variant);

        CommunityRoute base = routes.get(0);
        assertEquals(2, base.steps.size());
        RouteStep s0 = base.steps.get(0);

        assertEquals(2, s0.actionsOf(RouteAction.Kind.WALK).size());
        assertEquals(1, s0.actionsOf(RouteAction.Kind.ETHERWARP).size());
        assertEquals(2, s0.actionsOf(RouteAction.Kind.BREAKER).size());
        assertEquals(1, s0.actionsOf(RouteAction.Kind.SUPERBOOM).size());
        assertEquals(1, s0.actionsOf(RouteAction.Kind.PEARL).size());
        assertEquals(1, s0.actionsOf(RouteAction.Kind.INTERACT).size());

        RouteAction pearl = s0.actionsOf(RouteAction.Kind.PEARL).get(0);
        assertEquals(40.5, pearl.pos.x, 1e-6);
        assertEquals(90.0f, pearl.yaw, 1e-4);
        assertEquals(-35.5f, pearl.pitch, 1e-4);

        assertNotNull(s0.secret);
        assertEquals("item", s0.secret.type);
        assertEquals(25, s0.secret.pos.x, 1e-6);
        assertEquals("bat", base.steps.get(1).secret.type);
    }

    @Test
    void srmSkipsMalformedSteps() {
        RoutePack pack = new SrmRouteParser().parse("srm:test", "test.json", new StringReader(SRM));
        List<CommunityRoute> routes = pack.routesFor("Broken-Room-1");
        assertNotNull(routes);
        assertEquals(1, routes.size());
        CommunityRoute route = routes.get(0);
        // only the one well-formed step survives
        assertEquals(1, route.steps.size());
        assertEquals(1, route.steps.get(0).actionsOf(RouteAction.Kind.ETHERWARP).size());
    }

    private static final String ODIN = """
        {
          "Test Room": [
            {"blockPos": {"x": 5, "y": 63, "z": 23}, "color": "#FF5555FF",
             "filled": false, "depth": false, "aabb": {"minX":0,"minY":0,"minZ":0,"maxX":1,"maxY":1,"maxZ":1},
             "title": "bat"},
            {"blockPos": {"x": 15, "y": 63, "z": 15}, "color": "#AA00AAFF",
             "filled": false, "depth": true, "title": "2 down"},
            {"blockPos": {"x": 19, "y": 77, "z": 26}, "color": "#AA00AAFF",
             "filled": true, "title": "1 item"},
            {"blockPos": {"x": 8, "y": 70, "z": 8}, "color": "#55FF55FF",
             "filled": false, "title": "3 boom"},
            {"blockPos": {"x": 9, "y": 70, "z": 9}, "type": "ETHERWARP"},
            "garbage",
            {"title": "no blockpos"}
          ],
          "Empty Room": []
        }
        """;

    @Test
    void odinParsesNumberedAmbientAndTyped() {
        RoutePack pack = new OdinWaypointParser().parse("odin:test", "test.json", new StringReader(ODIN));
        assertEquals(RoutePack.Format.ODIN, pack.format);
        assertEquals(1, pack.roomCount()); // "Empty Room" produces no route

        List<CommunityRoute> routes = pack.routesFor("Test Room");
        assertNotNull(routes);
        CommunityRoute route = routes.get(0);
        // numbered steps 1,2,3 in order, then ambient steps (bat + etherwarp)
        List<RouteStep> ordered = route.steps.stream().filter(s -> !s.ambient).toList();
        assertEquals(3, ordered.size());
        assertEquals("1 item", ordered.get(0).secret.type);
        assertNull(ordered.get(1).secret); // "2 down" is a positional hint, not a secret
        assertEquals(1, ordered.get(2).actionsOf(RouteAction.Kind.SUPERBOOM).size());

        List<RouteStep> ambient = route.steps.stream().filter(s -> s.ambient).toList();
        assertEquals(2, ambient.size());
        // untyped "bat" waypoint is an ambient marker AND contributes a secret
        boolean hasBatSecret = ambient.stream().anyMatch(
                s -> s.secret != null && s.secret.type.contains("bat"));
        assertTrue(hasBatSecret);
        // typed ETHERWARP waypoint classifies even without a title
        boolean hasWarp = ambient.stream().flatMap(s -> s.actions.stream())
                .anyMatch(a -> a.kind == RouteAction.Kind.ETHERWARP);
        assertTrue(hasWarp);
    }
}
