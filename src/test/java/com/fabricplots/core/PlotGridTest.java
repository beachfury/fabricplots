package com.fabricplots.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

class PlotGridTest {
    @Test
    void mapsNegativeCoordinatesWithFloorMath() {
        assertEquals(new PlotPos(0, 0), PlotManager.plotAt(-PlotConfig.SIDEWALK_DEPTH, -PlotConfig.SIDEWALK_DEPTH));
        assertEquals(new PlotPos(-1, -1), PlotManager.plotAt(-PlotConfig.SIDEWALK_DEPTH - 1, -PlotConfig.SIDEWALK_DEPTH - 1));
        assertArrayEquals(new int[]{-PlotConfig.STEP + PlotConfig.PLOT_SIZE / 2,
                -PlotConfig.STEP + PlotConfig.PLOT_SIZE / 2}, PlotManager.homeXZ(new PlotPos(-1, -1)));
    }

    @Test
    void distinguishesBuildablePlotFromRoadBand() {
        assertTrue(PlotManager.isInsidePlot(0, 0));
        assertTrue(PlotManager.isInsidePlot(-PlotConfig.SIDEWALK_DEPTH, 0));
        assertFalse(PlotManager.isInsidePlot(PlotConfig.PLOT_SIZE + PlotConfig.SIDEWALK_DEPTH, 0));
    }

    @Test
    void emptyRegistryAllocatesTheOriginFirst() {
        assertEquals(new PlotPos(0, 0), PlotManager.nextFree());
    }

    @Test
    void metadataMutationRollsBackWhenPersistenceIsUnavailable() {
        UUID owner = UUID.randomUUID();
        PlotData data = new PlotData(owner, "owner");
        data.name = "before";
        data.trusted.add(UUID.randomUUID());
        data.cells.add(new PlotPos(2, 3));

        assertFalse(PlotManager.update(data, plot -> {
            plot.name = "after";
            plot.trusted.clear();
            plot.cells.clear();
        }));
        assertEquals("before", data.name);
        assertEquals(1, data.trusted.size());
        assertEquals(java.util.Set.of(new PlotPos(2, 3)), data.cells);
    }
}
