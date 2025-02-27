package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.*;
import org.tradebot.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.tradebot.util.JsonParser.*;

public class PublicAPIService {

    private final Log log;
    private final PublicHttpClient publicHttpClient;
    private static PublicAPIService instance;

    public static PublicAPIService getInstance() {
        if (instance == null) {
            instance = new PublicAPIService();
        }
        return instance;
    }

    private PublicAPIService() {
        this.log = new Log();
        this.publicHttpClient = new PublicHttpClient();
    }

    public HTTPResponse<TreeMap<Long, MarketEntry>> getMarketDataPublicAPI(String symbol, String interval, int limit) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("interval", String.valueOf(interval));
        params.put("limit", String.valueOf(limit));

        var response = publicHttpClient.sendPublicRequest("/fapi/v1/klines", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseResponseMarketData(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Precision> fetchSymbolPrecision(String symbol) {
        var response = publicHttpClient.sendPublicRequest("/fapi/v1/exchangeInfo", "GET", new HashMap<>());
        if (response.isSuccess()) {
            Precision precision = parsePrecision(response.getValue(), symbol);
            if (precision == null)
                throw log.throwError("Precision not found");

            return HTTPResponse.success(response.getStatusCode(), precision);
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<OrderBook> getOrderBookPublicAPI(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("limit", String.valueOf(50));

        var response = publicHttpClient.sendPublicRequest("/fapi/v1/depth", "GET", params);
        if (response.isSuccess()) {
            log.info("Fetched snapshot from API.");
            return HTTPResponse.success(response.getStatusCode(), parseOrderBook(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Long> getBinanceServerTime() {
        HTTPResponse<String> response = publicHttpClient.sendPublicRequest("/fapi/v1/time", "GET", new HashMap<>());
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), new JSONObject(response.getValue()).getLong("serverTime"));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public void logAll() {
        publicHttpClient.logAll();
    }
}
