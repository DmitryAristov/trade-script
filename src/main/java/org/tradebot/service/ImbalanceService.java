package org.tradebot.service;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.ImbalanceStateCallback;
import org.tradebot.listener.MarketDataCallback;
import org.tradebot.listener.VolatilityCallback;
import org.tradebot.util.Log;
import org.tradebot.util.TimeFormatter;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.tradebot.service.TradingBot.*;

public class ImbalanceService implements VolatilityCallback, MarketDataCallback {

    public enum State {
        WAIT,
        PROGRESS,
        POTENTIAL_END_POINT,
        COMPLETED
    }

    private final Log log = new Log("imbalance_service");

    protected double priceChangeThreshold, speedThreshold;
    protected AtomicReference<State> currentState = new AtomicReference<>(State.WAIT);
    protected Imbalance currentImbalance = null;

    private final TreeMap<Long, MarketEntry> seconds = new TreeMap<>();
    private final TreeMap<Long, MarketEntry> largeData = new TreeMap<>();
    private final LinkedList<Imbalance> imbalances = new LinkedList<>();

    private double currentMinuteHigh = 0.;
    private double currentMinuteLow = Double.MAX_VALUE;
    private double currentMinuteVolume = 0;
    private long lastMinuteTimestamp = -1L;

    public ImbalanceService() {  }

    @Override
    public void notifyNewMarketEntry(long currentTime, MarketEntry currentEntry) {
        updateData(currentTime, currentEntry);

        try {
            switch (currentState.get()) {
                case WAIT -> handleWaitState(currentTime, currentEntry);
                case PROGRESS -> trackImbalanceProgress(currentTime, currentEntry);
                case POTENTIAL_END_POINT -> evaluatePossibleEndPoint(currentTime, currentEntry);
                case COMPLETED -> saveCompletedImbalanceAndResetState();
            }
        } catch (Exception e) {
            log.error("Failed to handle market price update", e);
        }
    }

    public static long simulationOpenTime = System.currentTimeMillis() + 60_000L;
    /**
     * Идем по секундным данным со свежих назад.
     * Находим первый имбаланс.
     * Идем еще назад и находим все имбалансы такого же типа.
     * Потом из всех находим с самым большим изменением цены, фильтруем все остальные по размеру > 0.75 * maxSize.
     * Потом из них находим с самой большой скоростью изменения цены.
     */
    private void handleWaitState(long currentTime, MarketEntry currentEntry) {
        // start generation random entry points
//        if (TEST_RUN) {
//            if (System.currentTimeMillis() > simulationOpenTime) {
//                simulationOpenTime = System.currentTimeMillis() + 30_000L;
//
//                double imbalanceSize = new Random().nextDouble(1000., 3000.);
//                double imbalanceStartPrice = currentEntry.low() + imbalanceSize;
//                double imbalanceEndPrice = currentEntry.low() - new Random().nextDouble(10., imbalanceSize * MAX_VALID_IMBALANCE_PART);
//                long imbalanceEndTime = currentTime - new Random().nextLong(MIN_POTENTIAL_COMPLETE_TIME, 10000L);
//                long imbalanceStartTime = new Random().nextLong(MIN_IMBALANCE_TIME_DURATION, Math.round(imbalanceEndTime - imbalanceSize / speedThreshold));
//
//                currentImbalance = new Imbalance(imbalanceStartTime, imbalanceStartPrice, imbalanceEndTime, imbalanceEndPrice, Imbalance.Type.DOWN);
//                currentState = State.PROGRESS;
//
//                log.info("Simulating imbalance in progress..." + currentImbalance);
//                if (callback != null)
//                    callback.notifyImbalanceStateUpdate(currentTime, currentState, currentImbalance);
//                return;
//            }
//            return;
//        }
        // end generation random entry points

        Imbalance detectedImbalance = findImbalance(currentTime, currentEntry);
        if (detectedImbalance != null) {
            processDetectedImbalance(currentTime, currentEntry, detectedImbalance);
        }
    }

    private Imbalance findImbalance(long currentTime, MarketEntry currentEntry) {
        for (Map.Entry<Long, MarketEntry> entry : seconds.descendingMap().entrySet()) {
            long previousTime = entry.getKey();
            MarketEntry previousEntry = entry.getValue();

            if (previousTime == currentTime) continue;

            if (currentEntry.high() - previousEntry.low() > priceChangeThreshold) {
                double priceChange = currentEntry.high() - previousEntry.low();
                double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);
                if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                    Imbalance imbalance = new Imbalance(previousTime, previousEntry.low(), currentTime, currentEntry.high(), Imbalance.Type.UP);
                    log.info(String.format("UP detected: %s", imbalance));
                    return imbalance;
                }
            } else if (previousEntry.high() - currentEntry.low() > priceChangeThreshold) {
                double priceChange = previousEntry.high() - currentEntry.low();
                double priceChangeSpeed = priceChange / (double) (currentTime - previousTime);

                if (priceChangeSpeed > speedThreshold * priceChangeThreshold / priceChange) {
                    Imbalance imbalance = new Imbalance(previousTime, previousEntry.high(), currentTime, currentEntry.low(), Imbalance.Type.DOWN);
                    log.info(String.format("DOWN detected: %s", imbalance));
                    return imbalance;
                }
            }
        }
        return null;
    }

    private void processDetectedImbalance(long currentTime, MarketEntry currentEntry, final Imbalance imbalance) {
        log.debug("Looking for initial point of detected imbalance...");
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

        log.debug(String.format("Looking for the best imbalance from %d found imbalances", imbalances.size()));
        currentImbalance = imbalances.stream()
                .filter(imbalance_ -> imbalance_.size() >= maxImbalanceSize * 0.75)
                .filter(this::isValid)
                .max(Comparator.comparing(Imbalance::speed))
                .orElse(null);

        if (currentImbalance != null) {
            log.debug(String.format("Found the best one: %s", currentImbalance));
            log.info("Changing state to PROGRESS");
            currentState.set(State.PROGRESS);
            if (callback != null)
                callback.notifyImbalanceStateUpdate(currentTime, currentState.get(), currentImbalance);
        } else {
            log.debug("Valid imbalance not found");
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
        log.debug("Validating imbalance...");

        boolean localExtremaBetweenStartEndPricesExists = seconds.subMap(imbalance.getStartTime(), imbalance.getEndTime()).entrySet().stream()
                .anyMatch(entry -> switch (imbalance.getType()) {
                    case UP -> entry.getValue().high() > imbalance.getEndPrice();
                    case DOWN -> entry.getValue().low() < imbalance.getEndPrice();
                });
        log.debug("Local price extrema between start and end exists: " +
                localExtremaBetweenStartEndPricesExists);

        boolean contrImbalanceExists = largeData.entrySet().stream()
                .filter(entry -> entry.getKey() <= imbalance.getStartTime() &&
                        entry.getKey() >= imbalance.getStartTime() - TIME_CHECK_CONTR_IMBALANCE)
                .anyMatch(entry -> switch (imbalance.getType()) {
                    case UP -> entry.getValue().high() > imbalance.getEndPrice() - imbalance.size() * 0.25;
                    case DOWN -> entry.getValue().low() < imbalance.getEndPrice() + imbalance.size() * 0.25;
                });
        log.debug("Contr imbalance present: " + contrImbalanceExists);

        boolean minDurationFit = imbalance.duration() > MIN_IMBALANCE_TIME_DURATION;
        log.debug("Minimum duration greater than allowed: " + minDurationFit);

        boolean highSizePointsExists = seconds.get(imbalance.getStartTime()).size() * 2 < imbalance.size() &&
                seconds.get(imbalance.getEndTime()).size() * 2 < imbalance.size();
        log.debug("Start or finish point has size greater than half imbalance: " + !highSizePointsExists);

        boolean result = minDurationFit && highSizePointsExists && !localExtremaBetweenStartEndPricesExists && !contrImbalanceExists;
        log.debug("Imbalance validation result: " + result);
        return result;
    }

    private void trackImbalanceProgress(long currentTime, MarketEntry currentEntry) {
        if (checkProgressCondition(currentTime, currentEntry)) {
            return;
        }
        if (checkCompleteCondition(currentTime)) {
            return;
        }

        if (checkPotentialEndPointCondition(currentTime, currentEntry)) {
            currentState.set(State.POTENTIAL_END_POINT);
            if (callback != null)
                callback.notifyImbalanceStateUpdate(currentTime, currentState.get(), currentImbalance);
        }
    }

    private boolean checkProgressCondition(long currentTime, MarketEntry currentEntry) {
        log.debug("Checking progress...");
//        if (TEST_RUN) {
//            return false;
//        }
        switch (currentImbalance.getType()) {
            case UP -> {
                if (currentEntry.high() > currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.high());
                    currentImbalance.setEndTime(currentTime);
                    currentState.set(State.PROGRESS);
                    if (callback != null)
                        callback.notifyImbalanceStateUpdate(currentTime, currentState.get(), currentImbalance);
                    log.debug("Imbalance is in progress");
                    return true;
                }
            }
            case DOWN -> {
                if (currentEntry.low() < currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.low());
                    currentImbalance.setEndTime(currentTime);
                    currentState.set(State.PROGRESS);
                    if (callback != null)
                        callback.notifyImbalanceStateUpdate(currentTime, currentState.get(), currentImbalance);
                    log.debug("Imbalance is in progress");
                    return true;
                }
            }
        }
        log.debug("Imbalance is not in progress");
        return false;
    }

    private boolean checkCompleteCondition(long currentTime) {
        log.debug("Checking complete condition...");
//        if (TEST_RUN) {
//            return false;
//        }
        double completeTime = currentImbalance.duration() * COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() > Math.max(completeTime, MIN_COMPLETE_TIME)) {
            log.info("Imbalance completed", currentTime);
            currentState.set(State.COMPLETED);
            if (callback != null)
                callback.notifyImbalanceStateUpdate(currentTime, currentState.get(), currentImbalance);
            return true;
        }
        log.debug("Imbalance is not completed");
        return false;
    }

    public boolean checkPotentialEndPointCondition(long currentTime, MarketEntry currentEntry) {
        log.debug("Checking potential endpoint condition...");
//        if (TEST_RUN) {
//            boolean is = currentTime - currentImbalance.getEndTime() > 2000L;
//            if (is)
//                log.info("Simulating potential entry point " + is);
//            return is;
//        }
        double relevantSize = currentImbalance.size() / priceChangeThreshold;
        double possibleDuration = currentImbalance.duration() / relevantSize * POTENTIAL_COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() < Math.max(possibleDuration, MIN_POTENTIAL_COMPLETE_TIME)) {
            log.debug("Computed duration is not fit condition");
            return false;
        }

        double currentImbalanceReturnPart = Math.abs(currentImbalance.getEndPrice() - currentEntry.average()) / currentImbalance.size();
        if (currentImbalanceReturnPart > MAX_VALID_IMBALANCE_PART) {
            log.debug("Exceed minimum valid returned price condition");
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
            log.debug("During imbalance price already returned to the first take level");
            return false;
        }

        log.info("Found potential entry point at: " + TimeFormatter.format(currentTime));
        currentImbalance.setComputedDuration(possibleDuration);
        return true;
    }

    private void evaluatePossibleEndPoint(long currentTime, MarketEntry currentEntry) {
//        if (TEST_RUN) {
//            if (currentTime - currentImbalance.getEndTime() > 20000L) {
//                currentState = State.COMPLETED;
//                log.info("Simulating completed imbalance...");
//                if (callback != null)
//                    callback.notifyImbalanceStateUpdate(currentTime, currentState, currentImbalance);
//            }
//            return;
//        }
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
            MarketEntry largeEntry = new MarketEntry(currentMinuteHigh, currentMinuteLow, currentMinuteVolume);
            largeData.put(currentTime, largeEntry);
            log.debug(String.format("New large market entry: time: %s, entry: %s", TimeFormatter.format(currentTime), largeEntry));
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
    public void notifyVolatilityUpdate(double volatility, double average) {
        this.priceChangeThreshold = average * PRICE_MODIFICATOR;
        this.speedThreshold = average * SPEED_MODIFICATOR;
    }

    private void saveCompletedImbalanceAndResetState() {
        imbalances.add(currentImbalance);
        resetImbalanceState();
    }

    private void resetImbalanceState() {
        log.info("Resetting state to initial.");
        currentImbalance = null;
        currentState.set(State.WAIT);
        if (callback != null)
            callback.notifyImbalanceStateUpdate(0, currentState.get(), null);
    }

    private ImbalanceStateCallback callback;

    public void setCallback(ImbalanceStateCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
        Map<Long, MarketEntry> snapshotSeconds;
        synchronized (seconds) {
            snapshotSeconds = new TreeMap<>(seconds);
        }
        Map<Long, MarketEntry> snapshotLargeData;
        synchronized (largeData) {
            snapshotLargeData = new TreeMap<>(largeData);
        }

        log.debug(String.format("""
                priceChangeThreshold: %.2f
                speedThreshold: %.2f
                currentState: %s
                currentImbalance: %s
                seconds: %s
                largeData: %s
                currentMinuteHigh: %.2f
                currentMinuteLow: %.2f
                currentMinuteVolume: %.2f
                lastMinuteTimestamp: %d
                imbalances: %s
                callback: %s
                """,
                priceChangeThreshold,
                speedThreshold,
                currentState.get(),
                currentImbalance,
                snapshotSeconds,
                snapshotLargeData,
                currentMinuteHigh,
                currentMinuteLow,
                currentMinuteVolume,
                lastMinuteTimestamp,
                imbalances,
                callback
        ));
    }
}
