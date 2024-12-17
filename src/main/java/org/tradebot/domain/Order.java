package org.tradebot.domain;

import org.tradebot.enums.ExecutionType;
import org.tradebot.enums.OrderType;
import org.tradebot.util.TimeFormatter;

import java.io.Serializable;
import java.util.Arrays;

public class Order implements Serializable {

    /**
     * Тип ордера: вверх или вниз
     */
    private OrderType type;

    /**
     * Тип ордера: рыночный или лимитный. Рыночный исполняется прямо в момент создания,
     * лимитный исполняется по цене исполнения (executionPrice)
     */
    private ExecutionType executionType;
    private double executionPrice;

    /**
     * Сумма денег ордера. Кредитное плечо уже учтено
     */
    private double moneyAmount;
    private Double price;
    private boolean filled = false;
    private boolean canceled = false;
    private double[] takeProfitPrices = new double[]{};
    private double stopLossPrice = -1;
    private long createTime;
    private Imbalance imbalance;

    public Order() { }

    /**
     * Исполнить ордер
     */
    public void fill() {
        this.filled = true;
    }

    /**
     * Отменить ордер
     */
    public void cancel() {
        this.canceled = true;
    }

    /**
     * Проверяет если лимитный ордер можно исполнить.
     * Возвращает ошибку если метод вызван на рыночном ордере.
     */
    public boolean isExecutable(MarketEntry currentEntry) {
        if (this.executionType == ExecutionType.LIMIT) {
            return switch (this.type) {
                case LONG -> this.executionPrice >= currentEntry.low();
                case SHORT -> this.executionPrice <= currentEntry.high();
            };
        } else {
            throw new RuntimeException("Market order executes at create time. It cannot be executable or not.");
        }
    }

    /**
     * Устанавливает цены закрытия позиции
     * @param stopLoss цена автоматического закрытия позиции в минус
     * @param takeProfitPrices цена автоматического закрытия позиции в плюс
     */
    public void setTP_SL(double[] takeProfitPrices, double stopLoss) {
        this.takeProfitPrices = takeProfitPrices;
        this.stopLossPrice = stopLoss;
    }

    public OrderType getType() {
        return type;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public boolean isFilled() {
        return filled;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public double[] getTakeProfitPrices() {
        return takeProfitPrices;
    }

    public double getStopLossPrice() {
        return stopLossPrice;
    }

    public void setExecutionPrice(double executionPrice) {
        this.executionPrice = executionPrice;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public void setType(OrderType type) {
        this.type = type;
    }

    public void setExecutionType(ExecutionType executionType) {
        this.executionType = executionType;
    }

    public double getMoneyAmount() {
        return moneyAmount;
    }

    public void setMoneyAmount(double moneyAmount) {
        this.moneyAmount = moneyAmount;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public Double getPrice() {
        return this.price;
    }

    public void setImbalance(Imbalance imbalance) {
        this.imbalance = imbalance;
    }

    public Imbalance getImbalance() {
        return imbalance;
    }

    @Override
    public String toString() {
        return String.format("""
                        Order
                           type :: %s
                           moneyAmount :: %.2f$
                           takeProfitPrice :: %s$
                           stopLossPrice :: %.2f$
                           createTime :: %s""",
                type,
                moneyAmount,
                Arrays.toString(takeProfitPrices),
                stopLossPrice,
                TimeFormatter.format(createTime));
    }
}

