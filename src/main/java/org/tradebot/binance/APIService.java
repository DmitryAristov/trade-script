package org.tradebot.binance;

import org.json.JSONObject;
import org.tradebot.domain.*;
import org.tradebot.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;

import static org.tradebot.binance.HttpClient.BASE_URL;
import static org.tradebot.util.JsonParser.*;

//TODO 429 error, unit tests
public class APIService {

    private final Log log = new Log();
    private final HttpClient httpClient;

    public APIService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HTTPResponse<Order, APIError> placeOrder(Order order) {
        Map<String, String> params = new HashMap<>();
        validateOrder(order);

        // mandatory params
        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide().toString());
        params.put("type", order.getType().toString());
        params.put("newClientOrderId", String.valueOf(order.getNewClientOrderId()));

        // optional params
        if (order.getPrice() != null) {
            params.put("price", String.valueOf(order.getPrice()));
        }
        if (order.getQuantity() != null) {
            params.put("quantity", String.valueOf(order.getQuantity()));
        }
        if (order.getStopPrice() != null) {
            params.put("stopPrice", String.valueOf(order.getStopPrice()));
        }
        if (order.isReduceOnly() != null) {
            params.put("reduceOnly", String.valueOf(order.isReduceOnly()));
        }
        if (order.isClosePosition() != null) {
            params.put("closePosition", String.valueOf(order.isClosePosition()));
        }
        if (order.getTimeInForce() != null) {
            params.put("timeInForce", String.valueOf(order.getTimeInForce()));
        }

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/order", "POST", params, true);

        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    private void validateOrder(Order order) {
        if (order.getSymbol() == null) {
            throw log.throwError("empty symbol :: " + order);
        }
        if (order.getPrice() != null && order.getPrice().doubleValue() <= 0) {
            throw log.throwError("Invalid price :: " + order.getPrice());
        }
        if (order.getQuantity() != null && order.getQuantity().doubleValue() <= 0) {
            throw log.throwError("Invalid quantity :: " + order.getQuantity());
        }
        if (order.getStopPrice() != null && order.getStopPrice().doubleValue() <= 0) {
            throw log.throwError("Invalid stop price :: " + order.getStopPrice());
        }
        if (order.getNewClientOrderId() == null) {
            throw log.throwError("client id is null");
        }
        if (order.getType() == Order.Type.LIMIT && order.getTimeInForce() == null) {
            throw log.throwError("timeInForce required for LIMIT order");
        }
    }

    public void cancelOrder(String symbol, String clientId) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("origClientOrderId", clientId);
        params.put("recvWindow", "5000");

        httpClient.sendRequest("/fapi/v1/order", "DELETE", params);
    }

    public void cancelOrder(Order order) {
        Map<String, String> params = new HashMap<>();

        params.put("symbol", order.getSymbol());
        if (order.getId() != null) {
            params.put("orderId", String.valueOf(order.getId()));
        } else {
            params.put("origClientOrderId", String.valueOf(order.getNewClientOrderId()));
        }
        params.put("recvWindow", "5000");

        httpClient.sendRequest("/fapi/v1/order", "DELETE", params);
    }

    public void cancelAllOpenOrders(String symbol) {
        Map<String, String> params = new HashMap<>();

        params.put("symbol", symbol);
        params.put("recvWindow", "5000");

        httpClient.sendRequest("/fapi/v1/allOpenOrders", "DELETE", params);
    }

    public HTTPResponse<Order, APIError> queryOrder(String symbol, String clientId) {
        Map<String, String> params = new HashMap<>();

        params.put("symbol", symbol);
        params.put("origClientOrderId", clientId);
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/order", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Order, APIError>  queryOrder(Order order) {
        Map<String, String> params = new HashMap<>();

        params.put("symbol", order.getSymbol());
        if (order.getId() != null) {
            params.put("orderId", String.valueOf(order.getId()));
        } else {
            params.put("origClientOrderId", String.valueOf(order.getNewClientOrderId()));
        }
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/order", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public void setLeverage(String symbol, int leverage) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        httpClient.sendRequest("/fapi/v1/leverage", "POST", params);
    }

    public HTTPResponse<Integer, APIError> getLeverage(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v2/positionRisk", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseLeverage(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Double, APIError> getAccountBalance() {
        Map<String, String> params = new HashMap<>();
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v2/balance", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseBalance(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<AccountInfo, APIError> getAccountInfo() {
        Map<String, String> params = new HashMap<>();
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v2/account", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseAccountInfo(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Position, APIError> getOpenPosition(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v3/positionRisk", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parsePosition(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<String, APIError> getUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/listenKey", "POST", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), new JSONObject(response.getValue()).getString("listenKey"));
        } else {
            return response;
        }
    }

    public void keepAliveUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        httpClient.sendRequest("/fapi/v1/listenKey", "PUT", params);
    }

    public void removeUserStreamKey() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        httpClient.sendRequest("/fapi/v1/listenKey", "DELETE", params);
    }

    public HTTPResponse<OrderBook, APIError> getOrderBookPublicAPI(String symbol) {
        var response = httpClient.sendPublicRequest(String.format("%s/fapi/v1/depth?symbol=%s&limit=%d", BASE_URL, symbol, 50));
        if (response.isSuccess()) {
            log.info("Fetched snapshot from API.");
            return HTTPResponse.success(response.getStatusCode(), parseOrderBook(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<TreeMap<Long, MarketEntry>, APIError> getMarketDataPublicAPI(String symbol, String interval, int limit) {
        HTTPResponse<String, APIError> response = httpClient
                .sendPublicRequest(String.format("%s/fapi/v1/klines?symbol=%s&limit=%d&interval=%s", BASE_URL, symbol, limit, interval));
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseResponseMarketData(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Precision, APIError> fetchSymbolPrecision(String symbol) {
        HTTPResponse<String, APIError> response = httpClient
                .sendPublicRequest(String.format("%s/fapi/v1/exchangeInfo", BASE_URL));
        if (response.isSuccess()) {
            Precision precision = parsePrecision(response.getValue(), symbol);
            if (precision == null)
                throw log.throwError("Precision not found");

            return HTTPResponse.success(response.getStatusCode(), precision);
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<List<Order>, APIError> getOpenOrders(String symbol) {
        Map<String, String> params = new HashMap<>();
        if (symbol != null) {
            params.put("symbol", symbol.toUpperCase());
        }
        params.put("recvWindow", "5000");
        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/openOrders", "GET", params);

        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrders(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }

    public HTTPResponse<Order, APIError> getOpenOrder(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", order.getSymbol());
        if (order.getId() != null) {
            params.put("orderId", String.valueOf(order.getId()));
        } else {
            params.put("origClientOrderId", String.valueOf(order.getNewClientOrderId()));
        }
        params.put("recvWindow", "5000");

        HTTPResponse<String, APIError> response = httpClient.sendRequest("/fapi/v1/openOrder", "GET", params);
        if (response.isSuccess()) {
            return HTTPResponse.success(response.getStatusCode(), parseOrder(response.getValue()));
        } else {
            return HTTPResponse.error(response.getStatusCode(), response.getError());
        }
    }


}
