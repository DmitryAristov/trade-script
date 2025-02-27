package org.tradebot.domain;

public record TradingAccountSettings(String apiKey, String apiSecret, String baseAsset, boolean customLeverage) {  }
