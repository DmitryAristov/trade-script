package org.tradebot.service;

import org.tradebot.domain.Imbalance;
import org.tradebot.enums.ImbalanceState;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.VolatilityListener;
import org.tradebot.util.Log;

import java.util.*;
import java.util.function.Predicate;

public class ImbalanceService implements VolatilityListener {

    /**
     * Время хранения ежесекундных данных (1000мс * 60с * 5м = 5 минут).
     * Отдельная коллекция для поиска окончания размером 120 секунд.
     */
    public static final long DATA_LIVE_TIME = 10 * 60_000L;
    public static final long LARGE_DATA_LIVE_TIME = 60 * 60_000L;
    public static final long LARGE_DATA_ENTRY_SIZE = 15_000L;

    /**
     * Время за которое если не появилось нового минимума то считаем имбаланс завершенным (1000мс * 60с = 1 минута)
     */
    public static final double COMPLETE_TIME_MODIFICATOR = 0.5;
    public static final double POTENTIAL_COMPLETE_TIME_MODIFICATOR = 0.06; //TODO для быстрых и медленных нужна разная формула

    /**
     * Константы для расчета минимальной скорости и цены.
     * Формула: минимальная цена/скорость = [средняя цена] * [волатильность] * [константа]
     */
    public static final double SPEED_MODIFICATOR = 1E-7, PRICE_MODIFICATOR = 0.02;

    public static final double MAX_VALID_IMBALANCE_PART = 0.15;
    public static final int MIN_IMBALANCE_TIME_DURATION = 10;
    public static final long TIME_CHECK_CONTR_IMBALANCE = 60 * 60_000L;
    public static final long MIN_POTENTIAL_COMPLETE_TIME = 2_000L;
    public static final long MIN_COMPLETE_TIME = 60_000L;
    public static final double RETURNED_PRICE_IMBALANCE_PARTITION = 0.35;


    /**
     * Минимальное изменение цены и минимальная скорость изменения.
     * Пересчитывается каждый день на основе волатильности и средней цены.
     * Изменение цены в $, скорость изменения в $/миллисекунду
     */
    private double priceChangeThreshold, speedThreshold;


    private ImbalanceState currentState = ImbalanceState.WAIT;
    private Imbalance currentImbalance = null;


    private final TreeMap<Long, MarketEntry> seconds = new TreeMap<>();
    private final TreeMap<Long, MarketEntry> largeData = new TreeMap<>();
    private double currentMinuteHigh = 0.;
    private double currentMinuteLow = Double.MAX_VALUE;
    private double currentMinuteVolume = 0;
    private long lastMinuteTimestamp = -1L;

    private final LinkedList<Imbalance> imbalances = new LinkedList<>();

    public ImbalanceService() {
        Log.info(String.format("""
                        imbalance parameters:
                            complete time modificator :: %.3f
                            potential complete time modificator :: %.3f
                            speed modificator :: %s
                            price modificator :: %s
                            maximum valid imbalance part when open position :: %.3f
                            minimum imbalance time duration :: %ds
                            minimum potential complete time :: %d seconds
                            minimum complete time :: %d seconds
                            data live time :: %d minutes
                            large data live time :: %d minutes
                            large data entry size :: %d seconds
                            time in the past to check for contr-imbalance :: %d minutes
                            already returned price imbalance partition on potential endpoint check %.3f""",
                COMPLETE_TIME_MODIFICATOR,
                POTENTIAL_COMPLETE_TIME_MODIFICATOR,
                SPEED_MODIFICATOR,
                PRICE_MODIFICATOR,
                MAX_VALID_IMBALANCE_PART,
                MIN_IMBALANCE_TIME_DURATION,
                MIN_POTENTIAL_COMPLETE_TIME/1000,
                MIN_COMPLETE_TIME/1000,
                DATA_LIVE_TIME/60_000L,
                LARGE_DATA_LIVE_TIME/60_000L,
                LARGE_DATA_ENTRY_SIZE/1000,
                TIME_CHECK_CONTR_IMBALANCE/60_000L,
                RETURNED_PRICE_IMBALANCE_PARTITION));
    }

    public void onTick(long currentTime, MarketEntry currentEntry) {
        updateData(currentTime, currentEntry);
        switch (currentState) {
            case WAIT -> detectImbalance(currentTime, currentEntry);
            case PROGRESS -> trackImbalanceProgress(currentTime, currentEntry);
            case POTENTIAL_END_POINT -> evaluatePossibleEndPoint(currentTime, currentEntry);
            case COMPLETED -> saveCompletedImbalanceAndResetState();
        }
    }

    /**
     * Идем по секундным данным со свежих назад.
     * Находим первый имбаланс.
     * Идем еще назад и находим все имбалансы такого же типа.
     * Потом из всех находим с самым большим изменением цены, фильтруем все остальные по размеру > 0.75 * maxSize.
     * Потом из них находим с самой большой скоростью изменения цены.
     */
    private void detectImbalance(long currentTime, MarketEntry currentEntry) {
        NavigableMap<Long, MarketEntry> descendingData = seconds.descendingMap();
        Imbalance imbalance = null;
        for (long previousTime : descendingData.keySet()) {
            if (previousTime == currentTime) {
                continue;
            }
            MarketEntry previousEntry = descendingData.get(previousTime);

            if (currentEntry.high() - previousEntry.low() > priceChangeThreshold) {
                double priceChange = currentEntry.high() - previousEntry.low();
                double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);
                if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                    imbalance = new Imbalance(previousTime, previousEntry.low(), currentTime, currentEntry.high(), Imbalance.Type.UP);
                    break;
                }
            } else if (previousEntry.high() - currentEntry.low() > priceChangeThreshold) {
                double priceChange = previousEntry.high() - currentEntry.low();
                double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);

                if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                    imbalance = new Imbalance(previousTime, previousEntry.high(), currentTime, currentEntry.low(), Imbalance.Type.DOWN);
                    break;
                }
            }
        }
        if (imbalance == null) {
            return;
        }
        findImbalanceStart(currentTime, currentEntry, imbalance);
    }

    private void findImbalanceStart(long currentTime, MarketEntry currentEntry, final Imbalance imbalance) {
        List<Imbalance> imbalances = new ArrayList<>();
        switch (imbalance.getType()) {
            case UP -> {
                long minEntryTime = seconds.subMap(seconds.firstKey(), true, imbalance.getStartTime(), true)
                        .entrySet()
                        .stream()
                        .min(Comparator.comparing(entry -> entry.getValue().low())).orElseThrow().getKey();
                NavigableMap<Long, MarketEntry> descendingSubData = seconds.descendingMap()
                        .subMap(imbalance.getStartTime(), false, minEntryTime, true);

                for (long previousTime : descendingSubData.keySet()) {
                    MarketEntry previousEntry = descendingSubData.get(previousTime);
                    if (currentEntry.high() - previousEntry.low() > priceChangeThreshold) {
                        double priceChange = currentEntry.high() - previousEntry.low();
                        double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);
                        if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                            imbalances.add(new Imbalance(previousTime, previousEntry.low(), currentTime, currentEntry.high(), Imbalance.Type.UP));
                        }
                    }
                }
            }
            case DOWN -> {
                long maxEntryTime = seconds.subMap(seconds.firstKey(), true, imbalance.getStartTime(), true)
                        .entrySet()
                        .stream()
                        .max(Comparator.comparing(entry -> entry.getValue().high())).orElseThrow().getKey();
                NavigableMap<Long, MarketEntry> descendingSubData = seconds.descendingMap()
                        .subMap(imbalance.getStartTime(), false, maxEntryTime, true);

                for (long previousTime : descendingSubData.keySet()) {
                    MarketEntry previousEntry = descendingSubData.get(previousTime);
                    if (previousEntry.high() - currentEntry.low() > priceChangeThreshold) {
                        double priceChange = previousEntry.high() - currentEntry.low();
                        double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);
                        if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                            imbalances.add(new Imbalance(previousTime, previousEntry.high(), currentTime, currentEntry.low(), Imbalance.Type.DOWN));
                        }
                    }
                }
            }
        }

        imbalances.add(imbalance);
        double maxImbalanceSize = imbalances.stream().max(Comparator.comparing(Imbalance::size)).get().size();
        currentImbalance = imbalances.stream()
                .filter(imbalance_ -> imbalance_.size() >= maxImbalanceSize * 0.75)
                .max(Comparator.comparing(Imbalance::speed))
                .orElseThrow();

        if (isValid(currentImbalance)) {
            currentState = ImbalanceState.PROGRESS;
            Log.debug(currentImbalance.getType() + " started: " + currentImbalance, seconds.lastKey());
        } else {
            currentImbalance = null;
        }
    }

    /**
     * Должно выполняться несколько условий:
     *  <li> первая и последняя свечи имбаланса не должны превышать половину его размера</li>
     *  <li> внутри имбаланса не должно быть экстремумов больше или меньше чем крайние точки</li>
     *  <li> до имбаланса в течение определенного времени не должно быть цены 80% от последней имбаланса. То есть до имбаланса не должно быть контр-имбаланса.</li>
     * @return true если валидный имбаланс
     */
    private boolean isValid(Imbalance imbalance) {
        return imbalance.duration() > MIN_IMBALANCE_TIME_DURATION &&
                seconds.get(imbalance.getStartTime()).size() * 2 < imbalance.size() &&
                seconds.get(imbalance.getEndTime()).size() * 2 < imbalance.size() &&
                seconds.subMap(imbalance.getStartTime(), imbalance.getEndTime()).entrySet().stream()
                        .noneMatch(entry -> switch (imbalance.getType()) {
                            case UP -> entry.getValue().high() > imbalance.getEndPrice();
                            case DOWN -> entry.getValue().low() < imbalance.getEndPrice();
                        }) &&
                largeData.entrySet().stream()
                        .filter(entry -> entry.getKey() <= imbalance.getStartTime() &&
                                entry.getKey() >= imbalance.getStartTime() - TIME_CHECK_CONTR_IMBALANCE)
                        .noneMatch(entry -> switch (imbalance.getType()) {
                            case UP -> entry.getValue().high() > imbalance.getEndPrice() - imbalance.size() * 0.25;
                            case DOWN -> entry.getValue().low() < imbalance.getEndPrice() + imbalance.size() * 0.25;
                        });
    }

    private void trackImbalanceProgress(long currentTime, MarketEntry currentEntry) {
        if (checkProgressCondition(currentTime, currentEntry)) {
            return;
        }
        if (checkCompleteCondition(currentTime)) {
            return;
        }

        if (checkPotentialEndPointCondition(currentTime, currentEntry)) {
            currentState = ImbalanceState.POTENTIAL_END_POINT;
        }
    }

    private boolean checkProgressCondition(long currentTime, MarketEntry currentEntry) {
        switch (currentImbalance.getType()) {
            case UP -> {
                if (currentEntry.high() >= currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.high());
                    currentImbalance.setEndTime(currentTime);
                    currentState = ImbalanceState.PROGRESS;
                    return true;
                }
            }
            case DOWN -> {
                if (currentEntry.low() <= currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.low());
                    currentImbalance.setEndTime(currentTime);
                    currentState = ImbalanceState.PROGRESS;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkCompleteCondition(long currentTime) {
        double completeTime = currentImbalance.duration() * COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() > Math.max(completeTime, MIN_COMPLETE_TIME)) {
            Log.debug(currentImbalance.getType() + " completed: " + currentImbalance, currentTime);
            currentImbalance.setCompleteTime(currentTime);
            currentState = ImbalanceState.COMPLETED;
//            updateUI(currentTime);
            return true;
        }
        return false;
    }

    public boolean checkPotentialEndPointCondition(long currentTime, MarketEntry currentEntry) {
        double relevantSize = currentImbalance.size() / priceChangeThreshold; //TODO брать не от минимального изменения а от средней цены
        double possibleDuration = currentImbalance.duration() / relevantSize * POTENTIAL_COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() < Math.max(possibleDuration, MIN_POTENTIAL_COMPLETE_TIME)) {
            return false;
        }

        if (Math.abs(currentImbalance.getEndPrice() - currentEntry.average()) / currentImbalance.size() > MAX_VALID_IMBALANCE_PART) {
            return false;
        }

        Predicate<MarketEntry> alreadyReturnedPriceCondition = switch (currentImbalance.getType()) {
            case UP -> {
                double averagePrice = currentImbalance.getEndPrice() - (currentImbalance.size() * RETURNED_PRICE_IMBALANCE_PARTITION);
                yield (marketEntry -> marketEntry.low() < averagePrice);
            }
            case DOWN -> {
                double averagePrice = currentImbalance.getEndPrice() + currentImbalance.size() * RETURNED_PRICE_IMBALANCE_PARTITION;
                yield (marketEntry -> marketEntry.high() > averagePrice);
            }
        };
        if (seconds.subMap(currentImbalance.getEndTime(), currentTime).values().stream().anyMatch(alreadyReturnedPriceCondition)) {
            return false;
        }

        currentImbalance.setComputedDuration(possibleDuration);
        return true;
    }

    private void evaluatePossibleEndPoint(long currentTime, MarketEntry currentEntry) {
        if (checkProgressCondition(currentTime, currentEntry)) {
            return;
        }
        checkCompleteCondition(currentTime);
    }

    private void updateData(long currentTime, MarketEntry currentEntry) {
        seconds.put(currentTime, currentEntry);
        if (currentTime - seconds.firstKey() > DATA_LIVE_TIME) {
            seconds.pollFirstEntry();
        }

        double priceHigh = currentEntry.high();
        double priceLow = currentEntry.low();
        double volume = currentEntry.volume();

        currentMinuteVolume += volume;
        if (priceHigh > currentMinuteHigh) {
            currentMinuteHigh = priceHigh;
        }
        if (priceLow < currentMinuteLow) {
            currentMinuteLow = priceLow;
        }
        if (lastMinuteTimestamp == -1) {
            lastMinuteTimestamp = currentTime;
        }
        if (currentTime - lastMinuteTimestamp > LARGE_DATA_ENTRY_SIZE) {
            largeData.put(currentTime, new MarketEntry(currentMinuteHigh, currentMinuteLow, currentMinuteVolume));
            currentMinuteHigh = 0;
            currentMinuteLow = Double.MAX_VALUE;
            currentMinuteVolume = 0;
            lastMinuteTimestamp = currentTime;
        }
        if (!largeData.isEmpty() && currentTime - largeData.firstKey() > LARGE_DATA_LIVE_TIME) {
            largeData.pollFirstEntry();
        }
    }

    @Override
    public void notify(double volatility, double average) {
        this.priceChangeThreshold = average * PRICE_MODIFICATOR;
        this.speedThreshold = average * SPEED_MODIFICATOR;

        Log.debug(String.format("new price change %.2f$ || speed %.2f$/minute", priceChangeThreshold, speedThreshold * 60_000L));
    }

    public ImbalanceState getCurrentState() {
        return currentState;
    }

    public Imbalance getCurrentImbalance() {
        return currentImbalance;
    }

    private void saveCompletedImbalanceAndResetState() {
        imbalances.add(currentImbalance);
        resetImbalanceState();
    }

    private void resetImbalanceState() {
        currentImbalance = null;
        currentState = ImbalanceState.WAIT;
    }

    public LinkedList<Imbalance> getImbalances() {
        return imbalances;
    }
}
