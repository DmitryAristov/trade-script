package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.ImbalanceStateCallback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tradebot.service.ImbalanceService.COMPLETE_TIME_MODIFICATOR;
import static org.tradebot.service.ImbalanceService.MIN_COMPLETE_TIME;

class ImbalanceServiceTest {

    private ImbalanceService imbalanceService;

    @Mock
    private ImbalanceStateCallback mockCallback;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        imbalanceService = new ImbalanceService();
        imbalanceService.setCallback(mockCallback);
    }

    @Test
    void testInitialState() {
        assertEquals(ImbalanceService.State.WAIT, imbalanceService.currentState);
    }

    @Test
    void testNotifyVolatilityUpdate() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100.0);

        assertEquals(100.0 * ImbalanceService.PRICE_MODIFICATOR, imbalanceService.priceChangeThreshold);
        assertEquals(100.0 * ImbalanceService.SPEED_MODIFICATOR, imbalanceService.speedThreshold);
    }

    @Test
    void testDetectImbalance() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        for (int i = 0; i < 21; i++) {
            imbalanceService.notifyNewMarketEntry(i * 1000, new MarketEntry(98000. + i * 100, 97900 + i * 100, 0));
        }
        assertEquals(ImbalanceService.State.PROGRESS, imbalanceService.currentState);
        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(eq(20000L), eq(ImbalanceService.State.PROGRESS),
                argThat(imbalance -> imbalance.getStartPrice() == 97900. &&
                        imbalance.getEndPrice() == 100000. &&
                        imbalance.getStartTime() == 0L &&
                        imbalance.getEndTime() == 20000L));
    }

    @Test
    void testTrackImbalanceProgress() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        for (int i = 0; i < 22; i++) {
            imbalanceService.notifyNewMarketEntry(i * 1000, new MarketEntry(98000. + i * 100, 97900 + i * 100, 0));
        }
        assertEquals(ImbalanceService.State.PROGRESS, imbalanceService.currentState);
        verify(mockCallback).notifyImbalanceStateUpdate(eq(20000L), eq(ImbalanceService.State.PROGRESS), any());
        assertEquals(21000L, imbalanceService.currentImbalance.getEndTime());
        assertEquals(100100., imbalanceService.currentImbalance.getEndPrice());
    }

    @Test
    void testPotentialEndPointDetection() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        MarketEntry lastEntry = null;
        long lastTime = 0L;
        for (int i = 0; i < 22; i++) {
            lastEntry = new MarketEntry(98000. + i * 100, 97900 + i * 100, 0);
            lastTime = i * 1000;
            imbalanceService.notifyNewMarketEntry(lastTime, lastEntry);
        }
        for (int i = 1; i < 4; i++) {
            imbalanceService.notifyNewMarketEntry(lastTime + i * 1000L, lastEntry);
        }

        assertEquals(ImbalanceService.State.POTENTIAL_END_POINT, imbalanceService.currentState);
        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(eq(20000L), eq(ImbalanceService.State.PROGRESS), any());
        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(eq(23000L), eq(ImbalanceService.State.POTENTIAL_END_POINT), any());
        assertEquals(97900, imbalanceService.currentImbalance.getStartPrice());
        assertEquals(100100, imbalanceService.currentImbalance.getEndPrice());
        assertEquals(0L, imbalanceService.currentImbalance.getStartTime());
        assertEquals(21000L, imbalanceService.currentImbalance.getEndTime());
        assertEquals("1145", String.format("%.0f", imbalanceService.currentImbalance.getComputedDuration()));
    }

    @Test
    void testCompletedImbalance() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        MarketEntry lastEntry = null;
        long lastTime = 0L;
        for (int i = 0; i < 22; i++) {
            lastEntry = new MarketEntry(98000. + i * 100, 97900 + i * 100, 0);
            lastTime = i * 1000;
            imbalanceService.notifyNewMarketEntry(lastTime, lastEntry);
        }
        int i = 1;
        double completeTime = MIN_COMPLETE_TIME;
        long currentTime = lastTime;
        while (currentTime - imbalanceService.currentImbalance.getEndTime() <= Math.max(MIN_COMPLETE_TIME, completeTime)) {
            currentTime = lastTime + i * 1000L;
            imbalanceService.notifyNewMarketEntry(currentTime, lastEntry);
            completeTime = imbalanceService.currentImbalance.duration() * COMPLETE_TIME_MODIFICATOR;
            i++;
        }

        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(eq(20000L), eq(ImbalanceService.State.PROGRESS), any());
        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(eq(23000L), eq(ImbalanceService.State.POTENTIAL_END_POINT), any());
        verify(mockCallback, times(0)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.COMPLETED), any());

        assertEquals(ImbalanceService.State.COMPLETED, imbalanceService.currentState);
        assertEquals(97900, imbalanceService.currentImbalance.getStartPrice());
        assertEquals(100100, imbalanceService.currentImbalance.getEndPrice());
        assertEquals(0L, imbalanceService.currentImbalance.getStartTime());
        assertEquals(21000L, imbalanceService.currentImbalance.getEndTime());
        assertEquals("1145", String.format("%.0f", imbalanceService.currentImbalance.getComputedDuration()));
    }

    @Test
    void testSetCallback() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        MarketEntry lastEntry = null;
        long lastTime = 0L;
        for (int i = 0; i < 22; i++) {
            lastEntry = new MarketEntry(98000. + i * 100, 97900 + i * 100, 0);
            lastTime = i * 1000;
            imbalanceService.notifyNewMarketEntry(lastTime, lastEntry);
        }
        verify(mockCallback, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.PROGRESS), any());
    }

    @Test
    void testInvalidImbalance() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        MarketEntry lastEntry;
        long lastTime;
        for (int i = 0; i < 20; i++) {
            lastEntry = new MarketEntry(98000. + i * 100, 97900 + i * 100, 0);
            lastTime = i * 1000;
            imbalanceService.notifyNewMarketEntry(lastTime, lastEntry);
        }
        verify(mockCallback, times(0)).notifyImbalanceStateUpdate(anyLong(), any(), any());
        assertNull(imbalanceService.currentImbalance);
    }
}
