package org.tradebot;


import org.tradebot.domain.*;
import org.tradebot.enums.ExecutionType;
import org.tradebot.enums.ImbalanceState;
import org.tradebot.enums.OrderType;
import org.tradebot.service.ImbalanceService;
import org.tradebot.util.Log;

import java.util.*;

/**
 * Класс описывающий стратегию открытия и закрытия сделок на основе технического анализа
 */
public class Strategy {
    /**
     * Текущее состояние программы.
     * Lifecycle:
     *   1. ждет имбаланс
     *   2. имбаланс появился, ищет точку входа
     *   3. точка входа найдена, открывает позицию(и)
     *   4. ожидает закрытия позиции(й)
     */
    public enum State {
        WAIT_IMBALANCE,
        ENTRY_POINT_SEARCH,
        POSITIONS_OPENED,
        WAIT_POSITIONS_CLOSED
    }

    /**
     * Части от размера имбаланса для первого и второго тейка в случае если закрытие одной позиции происходит по частям.
     *  Пример: имбаланс был 55000$ -> 59000$. Тогда его размер = 4000$.
     *          При SHORT сделке первый тейк будет выставлен на 59000 - 4000 * 0.4 = 57400$.
     */
    private static final int TAKES_COUNT = 2;
    private static final double[] TAKE_PROFIT_THRESHOLDS = new double[]{0.35, 0.75};
    private static final double STOP_LOSS_MODIFICATOR = 0.01;
    private static final long POSITION_LIVE_TIME = 240 * 60_000L;

    private final ImbalanceService imbalanceService;
    private final TradingAPI tradingAPI;

    public State state = State.WAIT_IMBALANCE;

    public Strategy(ImbalanceService imbalanceService,
                    TradingAPI tradingAPI) {
        this.imbalanceService = imbalanceService;
        this.tradingAPI = tradingAPI;

        //noinspection ConstantValue
        if (TAKES_COUNT > TAKE_PROFIT_THRESHOLDS.length) {
            throw new RuntimeException("foreach take modifier must be defined");
        }

        Log.info(String.format("""
                        strategy parameters:
                            takes count :: %d
                            takes modifiers :: %s
                            stop modificator :: %.2f
                            position live time :: %d minutes""",
                TAKES_COUNT,
                Arrays.toString(TAKE_PROFIT_THRESHOLDS),
                STOP_LOSS_MODIFICATOR,
                POSITION_LIVE_TIME/60_000L));
    }

    public void onTick(long currentTime, MarketEntry currentEntry) throws Exception {
        switch (state) {
            case WAIT_IMBALANCE -> {
                /*
                 * Is imbalance present?
                 *    yes - change state to ENTRY_POINT_SEARCH
                 *    no - { return without state change }
                 */
                if (imbalanceService.getCurrentState() == ImbalanceState.PROGRESS) {
                    state = State.ENTRY_POINT_SEARCH;
                }
            }
            case ENTRY_POINT_SEARCH -> {
                /*
                 * Is imbalance completed?
                 *    yes - change state to POSSIBLE_ENTRY_POINT
                 *    no - { return without state change }
                 */
                if (imbalanceService.getCurrentState() == ImbalanceState.POTENTIAL_END_POINT) {
                    if (openPositions(currentTime, currentEntry)) {
                        state = State.POSITIONS_OPENED;
                    } else {
                        state = State.WAIT_IMBALANCE;
                    }
                }
            }
            case POSITIONS_OPENED -> {
                /*
                 * Wait for the price moving to stop loss or take profit.
                 * Control opened position.
                 * Position is closed?
                 *    yes - change state to IMBALANCE_IN_PROGRESS
                 *    no - { return without state change }
                 */
                List<Position> positions = tradingAPI.getOpenPositions();
                closeByTimeout(currentTime, currentEntry, positions);

                positions = tradingAPI.getOpenPositions();
                if (TAKES_COUNT == 1) {
                    state = State.WAIT_POSITIONS_CLOSED;
                } else {
                    if (positions.isEmpty()) {
                        // все закрылось в убыток -> снова ждем имбаланс
                        state = State.WAIT_IMBALANCE;
                    } else if (positions.size() == TAKES_COUNT - 1) {
                        // взят первый тейк -> ставим всем остальным без-убыток
                        positions.forEach(position -> {
                            if (position.getProfitLoss() > (position.getOpenFee() + position.getCloseFee()) * 2.) {
                                position.setZeroLoss();
                                state = State.WAIT_POSITIONS_CLOSED;
                            }
                        });
                    }
                }
            }
            case WAIT_POSITIONS_CLOSED -> {
                List<Position> positions = tradingAPI.getOpenPositions();
                closeByTimeout(currentTime, currentEntry, positions);

                positions = tradingAPI.getOpenPositions();
                if (positions.isEmpty()) {
                    state = State.WAIT_IMBALANCE;
                }
            }
        }
    }

    private boolean openPositions(long currentTime, MarketEntry currentEntry) throws Exception {
        if (!tradingAPI.getOpenPositions().isEmpty()) {
            throw new RuntimeException("Trying to open position while already opened " + tradingAPI.getOpenPositions().size());
        }


        Imbalance imbalance = imbalanceService.getCurrentImbalance();
        double imbalanceSize = imbalance.size();
        Log.debug("imbalance when open position :: " + imbalance);

        Order order = new Order();
        order.setImbalance(imbalance);

        order.setExecutionType(ExecutionType.LIMIT);

        // partially take profit and stop loss
        double stopLossPrice = imbalance.getEndPrice();
        double[] takeProfitPrices = new double[TAKES_COUNT];
        for (int i = 0; i < TAKES_COUNT; i++) {
            switch (imbalance.getType()) {
                case UP -> {
                    order.setType(OrderType.SHORT);
                    takeProfitPrices[i] = imbalance.getEndPrice() - TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize;
                    stopLossPrice += imbalanceSize * STOP_LOSS_MODIFICATOR;
                }
                case DOWN -> {
                    order.setType(OrderType.LONG);
                    takeProfitPrices[i] = imbalance.getEndPrice() + TAKE_PROFIT_THRESHOLDS[i] * imbalanceSize;
                    stopLossPrice -= imbalanceSize * STOP_LOSS_MODIFICATOR;
                }
            }

        }
        order.setTP_SL(takeProfitPrices, stopLossPrice);


        order.setCreateTime(currentTime);
        order.setMoneyAmount(calculatePositionSize());
        tradingAPI.createLimitOrder(order);

        return true;
    }

    private void closeByTimeout(long currentTime, MarketEntry currentEntry, List<Position> positions) {
        positions.forEach(position -> {
            if (currentTime - position.getOpenTime() > POSITION_LIVE_TIME) {
                Log.debug(String.format("close positions with timeout %d minutes", POSITION_LIVE_TIME / 60_000L));
                position.close(currentTime, currentEntry.average());
            }
        });
    }

    public double calculatePositionSize() throws Exception {
        return tradingAPI.getAccountBalance() * tradingAPI.getLeverage();
    }
}