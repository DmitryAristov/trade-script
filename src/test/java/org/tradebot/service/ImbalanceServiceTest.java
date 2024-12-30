package org.tradebot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.ImbalanceStateListener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImbalanceServiceTest {

    private ImbalanceService imbalanceService;

    @Mock
    private ImbalanceStateListener mockListener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        imbalanceService = new ImbalanceService();
        imbalanceService.subscribe(mockListener);
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
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), any(), any());
    }

    @Test
    void testTrackImbalanceProgress() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        for (int i = 0; i < 22; i++) {
            imbalanceService.notifyNewMarketEntry(i * 1000, new MarketEntry(98000. + i * 100, 97900 + i * 100, 0));
        }
        assertEquals(ImbalanceService.State.PROGRESS, imbalanceService.currentState);
        verify(mockListener, times(0)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.WAIT), any());
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.PROGRESS), any());
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
        for (int i = 1; i < 10; i++) {
            imbalanceService.notifyNewMarketEntry(lastTime + i * 1000L, lastEntry);
        }

        assertEquals(ImbalanceService.State.POTENTIAL_END_POINT, imbalanceService.currentState);
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.PROGRESS), any());
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.POTENTIAL_END_POINT), any());
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
        for (int i = 0; i < 61; i++) {
            imbalanceService.notifyNewMarketEntry(lastTime + (i + 1) * 1000L, lastEntry);
        }

        assertEquals(ImbalanceService.State.COMPLETED, imbalanceService.currentState);
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.PROGRESS), any());
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.POTENTIAL_END_POINT), any());
        verify(mockListener, times(0)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.COMPLETED), any());
    }

    @Test
    void testSubscribeUnsubscribeListeners() {
        imbalanceService.notifyVolatilityUpdate(0.05, 100000.);
        MarketEntry lastEntry = null;
        long lastTime = 0L;
        for (int i = 0; i < 22; i++) {
            lastEntry = new MarketEntry(98000. + i * 100, 97900 + i * 100, 0);
            lastTime = i * 1000;
            imbalanceService.notifyNewMarketEntry(lastTime, lastEntry);
        }
        verify(mockListener, times(1)).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.PROGRESS), any());
        imbalanceService.unsubscribe(mockListener);
        for (int i = 1; i < 10; i++) {
            imbalanceService.notifyNewMarketEntry(lastTime + i * 1000L, lastEntry);
        }
        verify(mockListener, never()).notifyImbalanceStateUpdate(anyLong(), eq(ImbalanceService.State.POTENTIAL_END_POINT), any());
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
        verify(mockListener, times(0)).notifyImbalanceStateUpdate(anyLong(), any(), any());
        assertNull(imbalanceService.currentImbalance);
    }
}
