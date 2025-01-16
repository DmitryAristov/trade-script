package org.tradebot.service;

import org.tradebot.domain.Imbalance;
import org.tradebot.domain.MarketEntry;
import org.tradebot.listener.ImbalanceStateCallback;
import org.tradebot.listener.MarketDataCallback;
import org.tradebot.listener.VolatilityCallback;
import org.tradebot.util.Log;
import org.tradebot.util.TimeFormatter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.Random;
import java.util.Map;

public class ImbalanceService implements VolatilityCallback, MarketDataCallback {

    public enum State {
        WAIT,
        PROGRESS,
        POTENTIAL_END_POINT,
        COMPLETED
    }

    public static final long DATA_LIVE_TIME = 10 * 60_000L;
    public static final long LARGE_DATA_LIVE_TIME = 60 * 60_000L;
    public static final long LARGE_DATA_ENTRY_SIZE = 15_000L;

    public static final double COMPLETE_TIME_MODIFICATOR = 0.5;
    public static final double POTENTIAL_COMPLETE_TIME_MODIFICATOR = 0.06;
    public static final double SPEED_MODIFICATOR = 1E-7, PRICE_MODIFICATOR = 0.02;
    public static final double MAX_VALID_IMBALANCE_PART = 0.15;

    public static final long MIN_IMBALANCE_TIME_DURATION = 10_000L;
    public static final long TIME_CHECK_CONTR_IMBALANCE = 60 * 60_000L;
    public static final long MIN_POTENTIAL_COMPLETE_TIME = 2_000L;
    public static final long MIN_COMPLETE_TIME = 60_000L;
    public static final double RETURNED_PRICE_IMBALANCE_PARTITION = 0.35;
    private final Log log = new Log("imbalance_service.log");

    protected double priceChangeThreshold, speedThreshold;
    protected State currentState = State.WAIT;
    protected Imbalance currentImbalance = null;

    private final TreeMap<Long, MarketEntry> seconds = new TreeMap<>();
    private final TreeMap<Long, MarketEntry> largeData = new TreeMap<>();
    private final LinkedList<Imbalance> imbalances = new LinkedList<>();

    private double currentMinuteHigh = 0.;
    private double currentMinuteLow = Double.MAX_VALUE;
    private double currentMinuteVolume = 0;
    private long lastMinuteTimestamp = -1L;


    public ImbalanceService() {
        log.info("Initial open position time: " + TimeFormatter.format(fakeOpenTime));

        log.info(String.format("""
                        ImbalanceService initialized with parameters:
                            complete time modificator :: %.3f
                            potential complete time modificator :: %.3f
                            speed modificator :: %s
                            price modificator :: %s
                            maximum valid imbalance part when open position :: %.3f
                            minimum imbalance time duration :: %d seconds
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
                TimeUnit.MILLISECONDS.toSeconds(MIN_IMBALANCE_TIME_DURATION),
                TimeUnit.MILLISECONDS.toSeconds(MIN_POTENTIAL_COMPLETE_TIME),
                TimeUnit.MILLISECONDS.toSeconds(MIN_COMPLETE_TIME),
                TimeUnit.MILLISECONDS.toMinutes(DATA_LIVE_TIME),
                TimeUnit.MILLISECONDS.toMinutes(LARGE_DATA_LIVE_TIME),
                TimeUnit.MILLISECONDS.toSeconds(LARGE_DATA_ENTRY_SIZE),
                TimeUnit.MILLISECONDS.toMinutes(TIME_CHECK_CONTR_IMBALANCE),
                RETURNED_PRICE_IMBALANCE_PARTITION));
    }

    @Override
    public void notifyNewMarketEntry(long currentTime, MarketEntry currentEntry) {
        updateData(currentTime, currentEntry);

        switch (currentState) {
            case WAIT -> handleWaitState(currentTime, currentEntry);
            case PROGRESS -> trackImbalanceProgress(currentTime, currentEntry);
            case POTENTIAL_END_POINT -> evaluatePossibleEndPoint(currentTime, currentEntry);
            case COMPLETED -> saveCompletedImbalanceAndResetState();
        }
    }

    public static long fakeOpenTime = System.currentTimeMillis() + 2 * 60_000L;
    /**
     * Идем по секундным данным со свежих назад.
     * Находим первый имбаланс.
     * Идем еще назад и находим все имбалансы такого же типа.
     * Потом из всех находим с самым большим изменением цены, фильтруем все остальные по размеру > 0.75 * maxSize.
     * Потом из них находим с самой большой скоростью изменения цены.
     */
    private void handleWaitState(long currentTime, MarketEntry currentEntry) {
        // start generation random entry points
        long openTime = System.currentTimeMillis() - fakeOpenTime;

        if (openTime > -50L && openTime < 50L) {
            log.info("Simulating potential entry point at " + TimeFormatter.format(fakeOpenTime));
            double imbalanceSize = new Random().nextDouble(500., 3000.);
            double imbalanceStartPrice = currentEntry.low() + imbalanceSize;
            double imbalanceEndPrice = currentEntry.low() - new Random().nextDouble(10., imbalanceSize * MAX_VALID_IMBALANCE_PART);
            long imbalanceEndTime = currentTime - new Random().nextLong(MIN_POTENTIAL_COMPLETE_TIME, 10000L);
            long imbalanceStartTime = new Random().nextLong(MIN_IMBALANCE_TIME_DURATION, Math.round(imbalanceEndTime - imbalanceSize / speedThreshold));

            currentImbalance = new Imbalance(imbalanceStartTime, imbalanceStartPrice, imbalanceEndTime, imbalanceEndPrice, Imbalance.Type.DOWN);
            currentState = State.POTENTIAL_END_POINT;

            log.info(String.format(currentImbalance.getType() + " detected: %s", currentImbalance));
            log.debug(currentImbalance.toString());
            if (callback != null)
                callback.notifyImbalanceStateUpdate(currentTime, currentState, currentImbalance);
            return;
        }
        if (fakeOpenTime > 0) {
            return;
        }
        // end generation random entry points




        log.debug("Looking for imbalance...");
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
        log.info("Looking for initial point of detected imbalance...");
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
        log.info(String.format("Looking for the best imbalance from %d found imbalances", imbalances.size()));
        currentImbalance = imbalances.stream()
                .filter(imbalance_ -> imbalance_.size() >= maxImbalanceSize * 0.75)
                .max(Comparator.comparing(Imbalance::speed))
                .orElseThrow();

        log.info(String.format("Found the best one: '%s'", currentImbalance));
        if (isValid(currentImbalance)) {
            log.info("Changing state to PROGRESS");
            currentState = State.PROGRESS;
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
        log.info("Validating imbalance...");

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
        log.info("Imbalance validation result: " + result);
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
            currentState = State.POTENTIAL_END_POINT;
            if (callback != null)
                callback.notifyImbalanceStateUpdate(currentTime, currentState, currentImbalance);
        }
    }

    private boolean checkProgressCondition(long currentTime, MarketEntry currentEntry) {
        log.info("Checking progress...");
        switch (currentImbalance.getType()) {
            case UP -> {
                if (currentEntry.high() > currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.high());
                    currentImbalance.setEndTime(currentTime);
                    currentState = State.PROGRESS;
                    log.info("Imbalance is in progress");
                    return true;
                }
            }
            case DOWN -> {
                if (currentEntry.low() < currentImbalance.getEndPrice()) {
                    currentImbalance.setEndPrice(currentEntry.low());
                    currentImbalance.setEndTime(currentTime);
                    currentState = State.PROGRESS;
                    log.info("Imbalance is in progress");
                    return true;
                }
            }
        }
        log.info("Imbalance is not in progress");
        return false;
    }

    private boolean checkCompleteCondition(long currentTime) {
        log.info("Checking complete condition...");
        double completeTime = currentImbalance.duration() * COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() > Math.max(completeTime, MIN_COMPLETE_TIME)) {
            log.info("Imbalance completed at " + TimeFormatter.format(currentTime));
            currentState = State.COMPLETED;
            return true;
        }
        log.info("Imbalance is not completed");
        return false;
    }

    public boolean checkPotentialEndPointCondition(long currentTime, MarketEntry currentEntry) {
        log.info("Checking potential endpoint condition...");
        double relevantSize = currentImbalance.size() / priceChangeThreshold;
        double possibleDuration = currentImbalance.duration() / relevantSize * POTENTIAL_COMPLETE_TIME_MODIFICATOR;
        if (currentTime - currentImbalance.getEndTime() < Math.max(possibleDuration, MIN_POTENTIAL_COMPLETE_TIME)) {
            log.info("Computed duration is not fit condition");
            return false;
        }

        double currentImbalanceReturnPart = Math.abs(currentImbalance.getEndPrice() - currentEntry.average()) / currentImbalance.size();
        if (currentImbalanceReturnPart > MAX_VALID_IMBALANCE_PART) {
            log.info("Exceed minimum valid returned price condition");
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
            log.info("During imbalance price already returned to the first take level");
            return false;
        }

        log.info("Found potential entry point at: " + TimeFormatter.format(currentTime));
        currentImbalance.setComputedDuration(possibleDuration);
        return true;
    }

    private void evaluatePossibleEndPoint(long currentTime, MarketEntry currentEntry) {
        currentState = State.COMPLETED;
        log.info("Simulation imbalance is completed");

//        if (checkProgressCondition(currentTime, currentEntry)) {
//            return;
//        }
//        checkCompleteCondition(currentTime);
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
        currentState = State.WAIT;
    }

    private ImbalanceStateCallback callback;

    public void setCallback(ImbalanceStateCallback callback) {
        this.callback = callback;
        log.info(String.format("Callback set: %s", callback.getClass().getName()));
    }

    public void logAll() {
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
                currentState,
                currentImbalance,
                seconds,
                largeData,
                currentMinuteHigh,
                currentMinuteLow,
                currentMinuteVolume,
                lastMinuteTimestamp,
                imbalances,
                callback
        ));
    }
}
