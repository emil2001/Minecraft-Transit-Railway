package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DashboardMapViewTest {
    @Test void negativeCoordinatesAndExactEdgesSelectOnlyIntersectingRegions() {
        List<Long> cells = DashboardMapView.cells(-256, -512, 0, -256, 256, 128);
        assertEquals(1, cells.size());
        assertEquals(-1, DashboardMapView.x(cells.get(0)));
        assertEquals(-2, DashboardMapView.z(cells.get(0)));
    }

    @Test void largeZoomedOutViewsStayBoundedAndStartAtTheirCenter() {
        List<Long> cells = DashboardMapView.cells(-64000, -64000, 64000, 64000, 256, 128);
        assertEquals(128, cells.size());
        assertEquals(0, DashboardMapView.x(cells.get(0)));
        assertEquals(0, DashboardMapView.z(cells.get(0)));
        assertEquals(128, cells.stream().distinct().count());
        assertEquals(cells, DashboardMapView.cells(-64000, -64000, 64000, 64000, 256, 128));
    }

    @Test void narrowViewsDoNotIncludeNeighboringColumns() {
        List<Long> cells = DashboardMapView.cells(0, -30000, 16, 30000, 256, 128);
        assertEquals(128, cells.size());
        assertTrue(cells.stream().allMatch(key -> DashboardMapView.x(key) == 0));
    }

    @Test void allFourMapEdgesClipCoordinatesAndTextureUvsTogether() {
        double[] clip = DashboardMapView.clip(-20, -30, 180, 170, 100, 100);
        assertArrayEquals(new double[]{0, 0, 100, 100, .1, .15, .6, .65}, clip, 1e-9);
    }

    @Test void whollyOutsideAndEmptyImagesAreNotDrawn() {
        assertNull(DashboardMapView.clip(110, 20, 120, 30, 100, 100));
        assertNull(DashboardMapView.clip(-20, -10, 0, 0, 100, 100));
        assertNull(DashboardMapView.clip(20, 20, 20, 30, 100, 100));
        assertTrue(DashboardMapView.cells(0, 0, 0, 50, 256, 128).isEmpty());
    }
}
